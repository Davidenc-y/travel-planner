package com.travel.planning.service;

import com.travel.common.dto.ChatResponseDTO;
import com.travel.common.entity.ChatMessage;
import com.travel.common.entity.ChatSession;
import com.travel.common.exception.BusinessException;
import com.travel.core.stream.TurnGate;
import com.travel.planning.memory.chat.ChatIntent;
import com.travel.planning.memory.sessionstore.SessionStorePort;
import com.travel.planning.memory.pipeline.ChatGuardStep;
import com.travel.planning.memory.pipeline.ChatPersistenceStep;
import com.travel.planning.memory.pipeline.ChatPreferenceStep;
import com.travel.planning.memory.pipeline.ChatKnowledgeStep;
import com.travel.planning.memory.pipeline.ChatIntentStep;
import com.travel.planning.memory.pipeline.ChatMemoryStep;
import com.travel.planning.memory.pipeline.ChatBudgetStep;
import com.travel.planning.memory.pipeline.ChatRoutingStep;
import com.travel.planning.memory.pipeline.ChatSessionGuardProperties;
import com.travel.planning.memory.pipeline.ChatTitleProperties;
import com.travel.planning.memory.shortterm.SessionFinalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 聊天服务
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService implements ChatStreamExecutor {

    // F67/B3-1：会话/消息持久化收口到 SessionStorePort，业务不再直连 Mapper
    private final SessionStorePort sessionStorePort;
    // F85 第二步：入口意图分类（PLANNING/REFINE/RECALL/PROFILE/CHAT/FUNCTIONAL）
    // M3-10：步骤 1 安全防护收敛到 ChatGuardStep（MessagePipeline 切片）
    private final ChatGuardStep chatGuardStep;
    // M3-11：步骤 2 持久化（会话校验 + 用户消息落库）收敛到 ChatPersistenceStep
    private final ChatPersistenceStep chatPersistenceStep;
    // M3-12：步骤 3 偏好（确定性偏好保存）收敛到 ChatPreferenceStep
    private final ChatPreferenceStep chatPreferenceStep;
    // M3-13：步骤 4 知识（切片+异步写入）收敛到 ChatKnowledgeStep
    private final ChatKnowledgeStep chatKnowledgeStep;
    // M3-14：步骤 5 意图（分类+追溯填充）收敛到 ChatIntentStep
    private final ChatIntentStep chatIntentStep;
    // M3-15：步骤 6 记忆（画像+历史/摘要组装）收敛到 ChatMemoryStep
    private final ChatMemoryStep chatMemoryStep;
    // M3-16：步骤 7 预算（检索注入+组装+四档预算兜底）收敛到 ChatBudgetStep
    private final ChatBudgetStep chatBudgetStep;
    // M3-17：步骤 8 路由（意图分派 recall/direct/supervisor）收敛到 ChatRoutingStep
    private final ChatRoutingStep chatRoutingStep;
    // M4-4：会话状态守卫（ARCHIVED 拒写开关）
    private final ChatSessionGuardProperties sessionGuardProps;
    // M5-1：会话标题生成配置（首条消息标题长度上限）
    private final ChatTitleProperties titleProps;
    // M4-4：会话收口器（close 后全量重算摘要，隐式待办+启动补偿）
    private final SessionFinalizer sessionFinalizer;

    /**
     * 创建会话
     */
    public String createSession(Long userId, String title) {
        return sessionStorePort.createSession(userId, title);
    }

    /**
     * 获取会话历史
     */
    public List<ChatMessage> getHistory(String sessionId) {
        return sessionStorePort.listMessages(sessionId);
    }

    /**
     * 发送消息并获取响应（无幂等键重载，原路径）。
     */
    public ChatResponseDTO sendMessage(String sessionId, String message, Long userId) {
        return sendMessage(sessionId, message, userId, null);
    }

    /**
     * 发送消息并获取响应（M4-3：支持消息级幂等）。
     *
     * <p>幂等语义见 {@link ChatPersistenceStep#beginTurn}：COMPLETED 重放 /
     * PENDING 40904 / FAILED 复用重跑 / 未命中同事务登记；兜底文案登记 FAILED
     * （重试重新执行，不重放兜底，M4-0-R1 评审 D3-1/D3-2）。</p>
     */
    public ChatResponseDTO sendMessage(String sessionId, String message, Long userId, String clientMessageId) {
        ChatStreamExecutor.ChatStreamPrepared prepared =
                prepareStream(userId, sessionId, message, clientMessageId);
        if (prepared.replay()) {
            // 命中 COMPLETED：直接重放，不落任何库（豁免会话状态校验——已归档会话也应可重放）
            return ChatResponseDTO.builder()
                    .sessionId(sessionId)
                    .response(prepared.gate().replayResponse())
                    .tokens(prepared.gate().replayTokens() == null ? 0 : prepared.gate().replayTokens())
                    .build();
        }
        ChatStreamExecutor.ChatStreamResult result =
                runStream(prepared, ChatProgressListener.NOOP);
        return ChatResponseDTO.builder()
                .sessionId(sessionId)
                .response(result.response())
                .tokens((int) result.aiTokens())
                .sessionTitle(result.sessionTitle())
                .build();
    }

    /**
     * M6：流式发送的准备阶段（步骤 1~2 + 标题联动），与 JSON 路径共用。
     *
     * <p>包含：40101/40302/40404 校验、Guard 注入检测、beginTurn 幂等门禁、
     * ARCHIVED 40902、用户消息追加、首条消息标题联动。</p>
     */
    @Override
    public ChatStreamExecutor.ChatStreamPrepared prepareStream(
            Long userId, String sessionId, String message, String clientMessageId) {
        // F52：防御脏 userId（兜底 0 会导致 user_id=0 画像/会话）。
        if (userId == null || userId <= 0) {
            throw new BusinessException(40101, "用户未登录");
        }
        // F90：调用前安全防护（Prompt 注入检测）→ MessagePipeline 步骤 1
        chatGuardStep.check(userId, message);
        // M3-11：步骤 2 持久化（会话校验 + 用户消息落库）
        ChatSession session = chatPersistenceStep.requireSession(sessionId);
        // M4-3/H-2：会话归属校验（越权访问他人会话）
        if (!userId.equals(session.getUserId())) {
            throw new BusinessException(40302, "无权访问该会话");
        }
        // M4-3：幂等门禁（在用户消息落库之前；未命中时用户消息已在门禁事务内追加）
        TurnGate gate = chatPersistenceStep.beginTurn(
                sessionId, userId, clientMessageId, message);
        String updatedSessionTitle = null;
        if (gate.proceed()) {
            // M4-4：ARCHIVED 会话拒绝新消息（40902；replay 已在上面豁免）
            if (sessionGuardProps.isRejectArchived() && "ARCHIVED".equals(session.getStatus())) {
                throw new BusinessException(40902, "会话已关闭");
            }
            if (!gate.userMessageAppended() && !gate.reuseUserMessage()) {
                // 无幂等键/开关关：原路径由服务端追加用户消息
                chatPersistenceStep.appendUserMessage(sessionId, message);
            }
            // M5-1：首条消息标题联动（短全量/长截断；仅默认标题生效，不覆盖手动标题；
            // 更新失败仅 WARN，不阻断发送主链路）
            String generatedTitle = buildSessionTitle(message, titleProps.getMaxLength());
            if (generatedTitle != null) {
                try {
                    if (sessionStorePort.updateTitleIfDefault(
                            sessionId, generatedTitle, SessionStorePort.DEFAULT_SESSION_TITLE) > 0) {
                        updatedSessionTitle = generatedTitle;
                    }
                } catch (Exception e) {
                    log.warn("[SessionTitle] 首条消息标题更新失败，继续发送: sessionId={}", sessionId, e);
                }
            }
        }
        return new ChatStreamExecutor.ChatStreamPrepared(
                sessionId, message, userId, clientMessageId, gate, updatedSessionTitle);
    }

    /**
     * M6：流式发送的执行阶段（步骤 3~9），通过 listener 输出思考与响应就绪事件。
     *
     * <p>与旧路径共用同一批步骤组件，异常时幂等记录置 FAILED（M4-3 语义不变）。</p>
     */
    @Override
    public ChatStreamExecutor.ChatStreamResult runStream(
            ChatStreamExecutor.ChatStreamPrepared prepared, ChatProgressListener listener) {
        String sessionId = prepared.sessionId();
        String message = prepared.message();
        Long userId = prepared.userId();
        String clientMessageId = prepared.clientMessageId();
        ChatProgressListener l = listener == null ? ChatProgressListener.NOOP : listener;
        try {
            l.onThinking("preference", "正在分析您的偏好…");
            // M3-12：步骤 3 偏好（确定性偏好保存；语义同 F71）
            chatPreferenceStep.saveIfPreference(userId, message);
            l.onThinking("knowledge", "正在整理会话知识…");
            // M3-13：步骤 4 知识（切片+异步写入；语义同 Phase C/F78 C1）
            chatKnowledgeStep.writeUserMessageAsync(sessionId, message);
            l.onThinking("intent", "正在理解您的意图…");
            // M3-14：步骤 5 意图（分类+追溯填充；语义同 F85/F89）
            ChatIntent intent = chatIntentStep.classify(sessionId, userId, message);

            l.onThinking("memory", "正在回顾会话记忆…");
            // M3-15：步骤 6 记忆（画像+历史/摘要组装；语义同 F50/F55/F57/F60）
            ChatMemoryStep.MemoryContext memory = chatMemoryStep.assemble(userId, sessionId);
            String profileContext = memory.profileContext();
            String historySection = memory.historySection();
            boolean summaryUsed = memory.summaryUsed();
            boolean summaryTriggered = memory.summaryTriggered();
            int turns = memory.turns();
            int totalHistoryTokens = memory.totalHistoryTokens();
            l.onThinking("budget", "正在组装上下文…");
            // M3-16：步骤 7 预算（检索注入+组装+四档预算兜底；语义同 F63/F66/F78/F83/F85）
            ChatBudgetStep.BudgetContext budget = chatBudgetStep.compose(sessionId, userId, intent,
                    message, profileContext, historySection);
            String composed = budget.composed();
            int inputTokens = budget.inputTokens();
            profileContext = budget.profileContext();
            historySection = budget.historySection();
            String candidates = budget.candidates();
            List<Map<String, Object>> sessionHits = budget.sessionHits();

            log.info("聊天输入组装完成: 总长度={}, 含画像={}, 含历史={}, 含摘要={}, 摘要触发={}, 历史轮数={}, 全量历史token={}, 注入token={}, 含知识库候选={}",
                    composed.length(), !profileContext.isBlank(), !historySection.isBlank(),
                    summaryUsed, summaryTriggered, turns, totalHistoryTokens, inputTokens,
                    !"[]".equals(candidates));

            l.onThinking("routing", "正在生成回答…");
            // M3-17/M6：步骤 8 路由（意图分派 recall/direct/supervisor；语义同 F85/F64/F27）
            // M6：JSON 路径（NOOP listener）保持阻塞式 route() 行为逐字等价；
            // 流式路径走 routeStream()——直答/回顾真 token 流，规划分块流。
            ChatRoutingStep.StreamRouteResult routed;
            if (l == ChatProgressListener.NOOP) {
                ChatRoutingStep.RouteResult blocking =
                        chatRoutingStep.route(intent, composed, userId, sessionHits);
                routed = new ChatRoutingStep.StreamRouteResult(blocking.response(),
                        blocking.aiTokens(), blocking.fallback(), false);
            } else {
                routed = chatRoutingStep.routeStream(intent, composed, userId,
                        sessionHits, l::onToken);
            }
            String response = routed.response();
            long aiTokens = routed.aiTokens();

            // M3-18：步骤 9 落库（AI 响应保存；语义同 F27）
            Long assistantMessageId = chatPersistenceStep.appendAssistantMessage(sessionId, response, aiTokens);

            // M4-3：按路由成败登记幂等终态（兜底文案→FAILED，真实回答→COMPLETED）
            if (routed.fallback()) {
                chatPersistenceStep.failTurn(clientMessageId);
            } else {
                chatPersistenceStep.completeTurn(clientMessageId, assistantMessageId);
            }

            // M6：真 token 流已在路由阶段逐增量输出，避免重复发送完整回答；
            // 规划/兜底路径在此统一 onResponse，由传输层分块。
            if (!routed.streamed()) {
                l.onResponse(response);
            }
            return new ChatStreamExecutor.ChatStreamResult(
                    response, aiTokens, routed.fallback(),
                    assistantMessageId, prepared.sessionTitle());
        } catch (Exception e) {
            // M4-3（复核观察项 2）：步骤 3~9 意外异常时幂等记录置 FAILED——
            // 否则 PENDING 悬挂，同键重试永远 40904（failTurn 对空键/开关关为 no-op）
            chatPersistenceStep.failTurn(clientMessageId);
            throw e;
        }
    }

    /**
     * 获取用户活跃会话列表
     */
    public List<ChatSession> listSessions(Long userId) {
        return sessionStorePort.listActiveByUserId(userId);
    }

    /**
     * M5-1：更新会话标题（前端双击编辑；归档会话也可改标题——只读历史仍展示）。
     */
    public void updateTitle(Long userId, String sessionId, String title) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(40101, "用户未登录");
        }
        ChatSession session = chatPersistenceStep.requireSession(sessionId);
        if (!userId.equals(session.getUserId())) {
            throw new BusinessException(40302, "无权访问该会话");
        }
        String normalized = title == null ? "" : title.trim();
        if (normalized.isEmpty()) {
            throw new BusinessException(40001, "会话标题不能为空");
        }
        if (normalized.length() > 200) {
            throw new BusinessException(40001, "会话标题不能超过200个字符");
        }
        int updated = sessionStorePort.updateTitle(sessionId, normalized);
        if (updated == 0) {
            throw new BusinessException(40404, "会话不存在: " + sessionId);
        }
    }

    /** M5-1：基于首条用户消息生成会话标题（不引 LLM：短全量、长截断） */
    static String buildSessionTitle(String message, int maxLength) {
        if (message == null) {
            return null;
        }
        String normalized = message.trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "…";
    }

    /** M4-4：关闭会话结果（archived=已归档；finalized=收口摘要已完成） */
    public record CloseSessionResult(boolean archived, boolean finalized) {
    }

    /**
     * M4-4/P1-1：关闭会话（显式触发；禁止前端 beforeunload 调用——刷新会误归档）。
     *
     * <p>幂等：已 ARCHIVED 直接返回；条件更新 ACTIVE→ARCHIVED 防并发双关；
     * 归档后同步尽力收口（超时/失败转隐式待办，启动补偿/空闲扫描兜底）。
     * history 查询不受归档影响（只读）。</p>
     */
    public CloseSessionResult closeSession(Long userId, String sessionId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(40101, "用户未登录");
        }
        ChatSession session = chatPersistenceStep.requireSession(sessionId);
        if (!userId.equals(session.getUserId())) {
            throw new BusinessException(40302, "无权访问该会话");
        }
        if ("ARCHIVED".equals(session.getStatus())) {
            return new CloseSessionResult(true, session.getSummaryFinal() != null);
        }
        int updated = sessionStorePort.updateStatus(sessionId, "ACTIVE", "ARCHIVED");
        if (updated == 0) {
            // 并发 close：重读判定幂等语义
            ChatSession fresh = sessionStorePort.findBySessionId(sessionId);
            if (fresh != null && "ARCHIVED".equals(fresh.getStatus())) {
                return new CloseSessionResult(true, fresh.getSummaryFinal() != null);
            }
            throw new BusinessException(40902, "会话状态冲突，请稍后重试");
        }
        boolean finalized = sessionFinalizer.finalizeSession(sessionId);
        return new CloseSessionResult(true, finalized);
    }
}
