package com.travel.planning.memory.pipeline;

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
