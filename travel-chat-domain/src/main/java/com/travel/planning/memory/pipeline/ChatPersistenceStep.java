package com.travel.planning.memory.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.travel.common.entity.ChatMessage;
import com.travel.common.entity.ChatMessageIdem;
import com.travel.common.entity.ChatSession;
import com.travel.common.enums.ChatRole;
import com.travel.common.exception.BusinessException;
import com.travel.common.util.TextTokens;
import com.travel.core.stream.TurnGate;
import com.travel.planning.memory.sessionstore.SessionStorePort;
import com.travel.planning.repository.ChatMessageIdemMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * M3-11/M3-18：MessagePipeline 步骤 2「持久化」+ 步骤 9「落库」。
 * 会话校验 + 用户消息落库 + AI 响应落库从 ChatService 抽出为独立可测步骤。
 *
 * <p>M4-3/P0-3：新增消息级幂等门禁（beginTurn/completeTurn/failTurn）。
 * 检查点前移到 appendUserMessage 之前——用户消息落库与 PENDING 登记同事务，
 * 杜绝"超时重试重复追加用户消息"（M4-0-R1 评审 D3-1）。不带幂等键的请求
 * 走原路径（灰度双轨）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatPersistenceStep {

    private final SessionStorePort sessionStorePort;
    private final ChatMessageIdemMapper idemMapper;
    private final ChatIdempotencyProperties idemProps;

    /**
     * 校验会话存在；不存在抛 40404（语义与 ChatService 原实现一致）。
     */
    public ChatSession requireSession(String sessionId) {
        ChatSession session = sessionStorePort.findBySessionId(sessionId);
        if (session == null) {
            throw new BusinessException(40404, "会话不存在: " + sessionId);
        }
        return session;
    }

    /**
     * M4-3：轮次开始门禁（在 appendUserMessage 之前调用）。
     *
     * <p>命中 COMPLETED → 重放 assistant 响应（不落任何库，不看会话状态）；
     * 命中 PENDING → 40904（客户端同键退避重试，杜绝与在途请求双跑）；
     * 命中 FAILED → 状态回 PENDING，复用原用户消息重新执行；
     * 未命中 → 同事务追加用户消息 + 登记 PENDING（唯一键兜底并发双发）。
     * 幂等键为空或开关关 → 全新执行（原路径）。</p>
     */
    @Transactional
    public TurnGate beginTurn(String sessionId, Long userId, String clientMessageId, String message) {
        if (clientMessageId == null || clientMessageId.isBlank() || !idemProps.isEnabled()) {
            return TurnGate.fresh();
        }
        ChatMessageIdem existing = idemMapper.selectById(clientMessageId);
        if (existing != null) {
            if (!sessionId.equals(existing.getSessionId())) {
                throw new BusinessException(40302, "幂等键已绑定其他会话: " + clientMessageId);
            }
            switch (existing.getStatus() == null ? "" : existing.getStatus()) {
                case ChatMessageIdem.STATUS_COMPLETED -> {
                    return replayOf(existing);
                }
                case ChatMessageIdem.STATUS_PENDING -> throw new BusinessException(40904, "消息处理中，请稍后重试");
                default -> { // FAILED：重新执行，复用原用户消息，不重复追加
                    existing.setStatus(ChatMessageIdem.STATUS_PENDING);
                    existing.setAssistantMessageId(null);
                    existing.setUpdatedAt(LocalDateTime.now());
                    idemMapper.updateById(existing);
                    return TurnGate.reuse();
                }
            }
        }
        Long userMessageId = sessionStorePort.appendMessage(
                sessionId, ChatRole.USER, message, TextTokens.estimate(message));
        ChatMessageIdem row = new ChatMessageIdem();
        row.setClientMessageId(clientMessageId);
        row.setSessionId(sessionId);
        row.setUserMessageId(userMessageId);
        row.setStatus(ChatMessageIdem.STATUS_PENDING);
        row.setUpdatedAt(LocalDateTime.now());
        try {
            idemMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // 并发双发同键：先到者已登记 PENDING，后到者按"处理中"语义返回
            throw new BusinessException(40904, "消息处理中，请稍后重试");
        }
        return TurnGate.freshAppended();
    }

    /** COMPLETED 重放：取关联 assistant 消息内容；缺失（防御）按 FAILED 复用重跑 */
    private TurnGate replayOf(ChatMessageIdem row) {
        if (row.getAssistantMessageId() != null) {
            ChatMessage assistant = sessionStorePort.findMessageById(row.getAssistantMessageId());
            if (assistant != null && assistant.getContent() != null) {
                return TurnGate.replay(assistant.getContent(), assistant.getTokens());
            }
        }
        log.warn("[Idempotency] COMPLETED 记录缺失 assistant 消息，转为重新执行: key={}",
                row.getClientMessageId());
        row.setStatus(ChatMessageIdem.STATUS_FAILED);
        row.setUpdatedAt(LocalDateTime.now());
        idemMapper.updateById(row);
        return TurnGate.reuse();
    }

    /**
     * M4-3：轮次成功收口（路由产出真实回答，assistant 已落库）。
     */
    public void completeTurn(String clientMessageId, Long assistantMessageId) {
        if (clientMessageId == null || clientMessageId.isBlank() || !idemProps.isEnabled()) {
            return;
        }
        ChatMessageIdem row = idemMapper.selectById(clientMessageId);
        if (row == null) {
            return;
        }
        row.setStatus(ChatMessageIdem.STATUS_COMPLETED);
        row.setAssistantMessageId(assistantMessageId);
        row.setUpdatedAt(LocalDateTime.now());
        idemMapper.updateById(row);
    }

    /**
     * M4-3：轮次失败收口（路由返回兜底文案）——重试命中 FAILED 重新执行，不重放兜底。
     */
    public void failTurn(String clientMessageId) {
        if (clientMessageId == null || clientMessageId.isBlank() || !idemProps.isEnabled()) {
            return;
        }
        ChatMessageIdem row = idemMapper.selectById(clientMessageId);
        if (row == null) {
            return;
        }
        row.setStatus(ChatMessageIdem.STATUS_FAILED);
        row.setAssistantMessageId(null);
        row.setUpdatedAt(LocalDateTime.now());
        idemMapper.updateById(row);
    }

    /**
     * M6-36：中断登记——PENDING → FAILED（复用既有失败语义：同键重试重新执行）。
     *
     * @return 是否发生 PENDING→FAILED 迁移；COMPLETED/FAILED/不存在均不动作
     */
    @Transactional
    public boolean markInterrupted(String sessionId, String clientMessageId) {
        if (clientMessageId == null || clientMessageId.isBlank() || !idemProps.isEnabled()) {
            return false;
        }
        ChatMessageIdem row = idemMapper.selectById(clientMessageId);
        if (row == null || !sessionId.equals(row.getSessionId())) {
            return false;
        }
        if (ChatMessageIdem.STATUS_PENDING.equals(row.getStatus())) {
            row.setStatus(ChatMessageIdem.STATUS_FAILED);
            row.setAssistantMessageId(null);
            row.setUpdatedAt(LocalDateTime.now());
            idemMapper.updateById(row);
            return true;
        }
        return false;
    }

    /**
     * M6-40：用户停止 → PENDING → INTERRUPTED（可恢复：同键重试从断点续跑）。
     *
     * @return 是否发生 PENDING→INTERRUPTED 迁移
     */
    @Transactional
    public boolean markTurnInterrupted(String sessionId, String clientMessageId) {
        if (clientMessageId == null || clientMessageId.isBlank() || !idemProps.isEnabled()) {
            return false;
        }
        ChatMessageIdem row = idemMapper.selectById(clientMessageId);
        if (row == null || !sessionId.equals(row.getSessionId())) {
            return false;
        }
        if (ChatMessageIdem.STATUS_PENDING.equals(row.getStatus())) {
            row.setStatus(ChatMessageIdem.STATUS_INTERRUPTED);
            row.setAssistantMessageId(null);
            row.setUpdatedAt(LocalDateTime.now());
            idemMapper.updateById(row);
            return true;
        }
        return false;
    }

    /**
     * M6-42：按会话 + 幂等键查询轮次登记（会话不匹配视为不存在）。
     *
     * <p>供 ChatService.getTurnStatus 恢复重试入口使用；幂等开关关闭时
     * 无登记记录可查，返回 null。</p>
     */
    public ChatMessageIdem findTurn(String sessionId, String clientMessageId) {
        if (clientMessageId == null || clientMessageId.isBlank() || !idemProps.isEnabled()) {
            return null;
        }
        ChatMessageIdem row = idemMapper.selectById(clientMessageId);
        if (row == null || !sessionId.equals(row.getSessionId())) {
            return null;
        }
        return row;
    }

    /**
     * M6-47：查询会话最近一个 INTERRUPTED 轮次（按 updated_at 倒序）。
     *
     * <p>供刷新/重进会话恢复重试入口使用（前端不依赖本地 key）；
     * 新消息终止在途会把旧轮次置 FAILED，因此该查询天然排除
     * "重试已永久消失"的轮次。</p>
     */
    public ChatMessageIdem findLatestInterrupted(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || !idemProps.isEnabled()) {
            return null;
        }
        List<ChatMessageIdem> rows = idemMapper.selectList(
                Wrappers.lambdaQuery(ChatMessageIdem.class)
                        .eq(ChatMessageIdem::getSessionId, sessionId)
                        .eq(ChatMessageIdem::getStatus, ChatMessageIdem.STATUS_INTERRUPTED)
                        .orderByDesc(ChatMessageIdem::getUpdatedAt)
                        .last("LIMIT 1"));
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * M6-39：新消息到来时终止同会话其他在途轮次（PENDING → FAILED）。
     *
     * @param excludeClientMessageId 当前新消息自身的幂等键（避免误杀自己）
     * @return 被终止的 clientMessageId 列表（调用方据此写 Redis 中断标记）
     */
    @Transactional
    public List<String> markSessionInterrupted(String sessionId, String excludeClientMessageId) {
        if (sessionId == null) {
            return List.of();
        }
        LambdaQueryWrapper<ChatMessageIdem> wrapper = Wrappers.lambdaQuery(ChatMessageIdem.class)
                .eq(ChatMessageIdem::getSessionId, sessionId)
                .eq(ChatMessageIdem::getStatus, ChatMessageIdem.STATUS_PENDING);
        if (excludeClientMessageId != null && !excludeClientMessageId.isBlank()) {
            wrapper.ne(ChatMessageIdem::getClientMessageId, excludeClientMessageId);
        }
        List<ChatMessageIdem> rows = idemMapper.selectList(wrapper);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        for (ChatMessageIdem row : rows) {
            row.setStatus(ChatMessageIdem.STATUS_FAILED);
            row.setAssistantMessageId(null);
            row.setUpdatedAt(LocalDateTime.now());
            idemMapper.updateById(row);
            keys.add(row.getClientMessageId());
        }
        return keys;
    }

    /**
     * 保存用户消息；tokens 采用 TextTokens 启发式估算（F27 口径，服务端无 tokenizer）。
     *
     * @return 消息 id（M4-3：幂等登记回填）
     */
    public Long appendUserMessage(String sessionId, String message) {
        return sessionStorePort.appendMessage(sessionId, ChatRole.USER, message, TextTokens.estimate(message));
    }

    /**
     * 保存 AI 响应；tokens 为本次全部 LLM 调用的真实 totalTokens（F27 口径）。
     *
     * @return 消息 id（M4-3：幂等登记回填）
     */
    public Long appendAssistantMessage(String sessionId, String response, long aiTokens) {
        return sessionStorePort.appendMessage(sessionId, ChatRole.ASSISTANT, response, (int) aiTokens);
    }
}
