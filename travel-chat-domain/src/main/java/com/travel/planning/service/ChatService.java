package com.travel.planning.service;

import com.travel.common.dto.ChatResponseDTO;
import com.travel.common.entity.ChatMessage;
import com.travel.common.entity.ChatMessageIdem;
import com.travel.common.entity.ChatSession;
import com.travel.common.exception.BusinessException;
import com.travel.common.exception.ErrorCode;
import com.travel.core.stream.TurnGate;
import com.travel.aigateway.core.GatewayException;
import com.travel.aigateway.core.ModelRegistry;
import com.travel.aigateway.route.ModelRoutingContext;
import com.travel.planning.cancellation.TurnCancellationBroadcaster;
import com.travel.planning.memory.chat.ChatIntent;
import com.travel.planning.memory.sessionstore.SessionStorePort;
import com.travel.planning.memory.pipeline.ChatBreakpointStore;
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
import com.travel.planning.trace.ModelRouteTracker;
import com.travel.planning.trace.TraceContext;
import io.lettuce.core.RedisCommandInterruptedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    // M6-36：轮次中断标记与断点快照（Redis 临时态）
    private final ChatBreakpointStore breakpointStore;
    // M6-40：在途轮次取消登记表（内存，key=clientMessageId）
    private final TurnCancellationRegistry cancellationRegistry;
    // M6-44：跨实例取消广播（Redis Pub/Sub 推送加速；权威仍为 DB + Redis 标记）
    private final TurnCancellationBroadcaster cancellationBroadcaster;
    // M7：模型注册表（D6：请求级 model 入口快速失败校验）
    private final ModelRegistry modelRegistry;
    // M7：实际路由模型追溯记录（direct 路径由 runStream 包裹捕获）
    private final ModelRouteTracker modelRouteTracker;

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
        return sendMessage(sessionId, message, userId, clientMessageId, null);
    }

    /**
     * 发送消息并获取响应（M4-3 幂等 + M7 请求级模型）。
     */
    public ChatResponseDTO sendMessage(String sessionId, String message, Long userId,
                                       String clientMessageId, String model) {
        ChatStreamExecutor.ChatStreamPrepared prepared =
                prepareStream(userId, sessionId, message, clientMessageId, model);
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
            Long userId, String sessionId, String message, String clientMessageId, String model) {
        // F52：防御脏 userId（兜底 0 会导致 user_id=0 画像/会话）。
        if (userId == null || userId <= 0) {
            throw new BusinessException(40101, "用户未登录");
        }
        // M7 D6：请求级 model 必须在注册表且 selectable，否则入口快速失败
        validateModel(model);
        // F90：调用前安全防护（Prompt 注入检测）→ MessagePipeline 步骤 1
        chatGuardStep.check(userId, message);
        // M3-11：步骤 2 持久化（会话校验 + 用户消息落库）
        ChatSession session = requireOwnedSession(userId, sessionId);
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
            // M6-36：新轮次（非 FAILED 复用）清除同会话旧断点——旧任务的重试按钮随之失效
            if (!gate.reuseUserMessage()) {
                breakpointStore.clearSessionBreakpoints(sessionId);
                // M6-39：同时终止同会话其他在途轮次（防旧任务后台完成后幽灵落库）
                List<String> inFlight = chatPersistenceStep.markSessionInterrupted(
                        sessionId, clientMessageId);
                if (inFlight != null && !inFlight.isEmpty()) {
                    inFlight.forEach(key -> {
                        breakpointStore.markInterrupted(key);
                        cancellationRegistry.cancel(key);
                        cancellationBroadcaster.publishCancel(sessionId, key);
                    });
                    log.info("[ChatInterrupt] 新消息终止在途轮次: sessionId={}, keys={}",
                            sessionId, inFlight);
                }
            }
        }
        return new ChatStreamExecutor.ChatStreamPrepared(
                sessionId, message, userId, clientMessageId, gate, updatedSessionTitle, model);
    }

    /** M7 D6：未知/禁用/不可选模型 → 40005，不静默回退。 */
    private void validateModel(String model) {
        if (model == null || model.isBlank()) {
            return;
        }
        try {
            modelRegistry.requireSelectable(model);
        } catch (GatewayException e) {
            throw new BusinessException(ErrorCode.MODEL_NOT_FOUND.code(),
                    ErrorCode.MODEL_NOT_FOUND.message() + ": " + model);
        }
    }

    /**
     * M6-36：中断在途轮次（PENDING → FAILED + Redis 中断标记）。
     *
     * <p>断点快照由 runStream 在路由前写入；若中断发生时尚未写入（步骤 3~7），
     * 重试将按 FAILED 语义整体重跑。</p>
     */
    public void interruptTurn(Long userId, String sessionId, String clientMessageId) {
        ChatSession session = requireOwnedSession(userId, sessionId);
        if (clientMessageId == null || clientMessageId.isBlank()) {
            throw new BusinessException(40001, "幂等键不能为空");
        }
        boolean flipped = chatPersistenceStep.markTurnInterrupted(sessionId, clientMessageId);
        if (flipped) {
            breakpointStore.markInterrupted(clientMessageId);
            cancellationRegistry.cancel(clientMessageId);
            cancellationBroadcaster.publishCancel(sessionId, clientMessageId);
            log.info("[ChatInterrupt] 轮次已中断: sessionId={}, key={}", sessionId, clientMessageId);
        }
    }

    /**
     * M6-36：清除断点（用户发新消息时前端调用；prepareStream 侧另有双保险）。
     */
    public void clearBreakpoint(Long userId, String sessionId, String clientMessageId) {
        ChatSession session = requireOwnedSession(userId, sessionId);
        breakpointStore.clearBreakpoint(sessionId, clientMessageId);
        cancellationRegistry.cancel(clientMessageId);
        cancellationBroadcaster.publishCancel(sessionId, clientMessageId);
        log.info("[ChatInterrupt] 断点已清除: sessionId={}, key={}", sessionId, clientMessageId);
    }

    /**
     * M6-42：查询轮次状态（前端刷新后恢复重试入口）。
     *
     * <p>resumable = INTERRUPTED 且断点快照仍存在（Redis 30min TTL 窗口内）；
     * 新消息已把旧轮次置 FAILED，故返回 FAILED 时前端不显示重试。</p>
     */
    public TurnStatusResult getTurnStatus(Long userId, String sessionId, String clientMessageId) {
        ChatSession session = requireOwnedSession(userId, sessionId);
        if (clientMessageId == null || clientMessageId.isBlank()) {
            throw new BusinessException(40001, "幂等键不能为空");
        }
        ChatMessageIdem row = chatPersistenceStep.findTurn(sessionId, clientMessageId);
        if (row == null) {
            return new TurnStatusResult(null, false, null);
        }
        boolean hasBreakpoint =
                !breakpointStore.loadBreakpoint(sessionId, clientMessageId).isEmpty();
        boolean resumable =
                ChatMessageIdem.STATUS_INTERRUPTED.equals(row.getStatus()) && hasBreakpoint;
        String userMessage = null;
        if (row.getUserMessageId() != null) {
            ChatMessage m = sessionStorePort.findMessageById(row.getUserMessageId());
            if (m != null) {
                userMessage = m.getContent();
            }
        }
        return new TurnStatusResult(row.getStatus(), resumable, userMessage);
    }

    /** M6-42：轮次状态查询结果（status 为 null 表示无登记记录）。 */
    public record TurnStatusResult(String status, boolean resumable, String userMessage) {
    }

    /**
     * M6-47：会话最近可恢复中断轮次（刷新/重进会话恢复重试入口）。
     *
     * <p>浏览器刷新/关闭页面时前端不会执行 handleStop（localStorage 无 key），
     * 因此刷新恢复不能依赖前端本地状态——由后端按"INTERRUPTED + 断点存在"
     * 权威查询，前端进入会话时直接获取。</p>
     */
    public LatestInterruptedTurn getLatestInterruptedTurn(Long userId, String sessionId) {
        ChatSession session = requireOwnedSession(userId, sessionId);
        ChatMessageIdem row = chatPersistenceStep.findLatestInterrupted(sessionId);
        if (row == null) {
            return new LatestInterruptedTurn(null, null, false);
        }
        boolean hasBreakpoint =
                !breakpointStore.loadBreakpoint(sessionId, row.getClientMessageId()).isEmpty();
        String userMessage = null;
        if (row.getUserMessageId() != null) {
            ChatMessage m = sessionStorePort.findMessageById(row.getUserMessageId());
            if (m != null) {
                userMessage = m.getContent();
            }
        }
        return new LatestInterruptedTurn(
                row.getClientMessageId(), userMessage, hasBreakpoint && userMessage != null);
    }

    /** M6-47：最近可恢复中断轮次（clientMessageId 为 null 表示无可恢复轮次）。 */
    public record LatestInterruptedTurn(
            String clientMessageId, String userMessage, boolean resumable) {
    }

    /**
     * M6：流式发送的执行阶段（步骤 3~9），通过 listener 输出思考与响应就绪事件。
     *
     * <p>与旧路径共用同一批步骤组件，异常时幂等记录置 FAILED（M4-3 语义不变）。</p>
     */
    @Override
    public ChatStreamExecutor.ChatStreamResult runStream(
            ChatStreamExecutor.ChatStreamPrepared prepared, ChatProgressListener listener) {
        // M8-9j：可观测性——请求级模型在流式入口的真实值（前端是否携带模型的关键证据）
        log.info("[ChatModel] 请求模型: sessionId={}, key={}, model={}",
                prepared.sessionId(), prepared.clientMessageId(), prepared.model());
        return ModelRoutingContext.runWith(prepared.model(), () -> {
            try {
                return runStreamInternal(prepared, listener);
            } finally {
                // M8-9j：异常（如额度 403）也必须记录实际路由模型——
                // 否则 t_agent_trace.model_name 停留在默认值，无法区分“请求未带模型”
                // 与“带了模型但路由失败”
                recordRoutedModel();
            }
        });
    }

    /** M7：direct 路径在同一线程完成路由，把实际模型写入追溯（图流路径由拦截器记录）。 */
    private void recordRoutedModel() {
        String routed = ModelRoutingContext.routed();
        if (routed == null || !TraceContext.active()) {
            return;
        }
        modelRouteTracker.record(TraceContext.current().requestId, routed);
    }

    private ChatStreamExecutor.ChatStreamResult runStreamInternal(
            ChatStreamExecutor.ChatStreamPrepared prepared, ChatProgressListener listener) {
        String sessionId = prepared.sessionId();
        String message = prepared.message();
        Long userId = prepared.userId();
        String clientMessageId = prepared.clientMessageId();
        ChatProgressListener l = listener == null ? ChatProgressListener.NOOP : listener;
        // M6-40：在途轮次登记取消令牌（finally 移除）
        TurnCancellation cancellation = cancellationRegistry.register(clientMessageId);
        if (cancellation == null) {
            cancellation = TurnCancellation.NOOP;
        }
        // M6-44：Redis 中断标记作为跨实例权威兜底——Pub/Sub 广播丢失/订阅未建立时，
        // 图流节点边界与拦截器仍能读到标记并停止（external check 由 isCancelled 组合）
        cancellation.attachExternalCancelCheck(
                () -> breakpointStore.isInterrupted(clientMessageId));
        try {
            // M6-36：重试先清除旧中断标记，避免恢复时被残留标记再次取消
            breakpointStore.clearInterrupt(clientMessageId);

            ChatIntent intent;
            String profileContext;
            String historySection;
            String composed;
            int inputTokens;
            String candidates;
            List<Map<String, Object>> sessionHits;

            // M6-36：断点恢复——跳过步骤 3~7，直达路由（复用已组装上下文）
            Map<String, Object> breakpoint = breakpointStore.loadBreakpoint(sessionId, clientMessageId);
            if (!breakpoint.isEmpty()) {
                l.onThinking("resume", "正在从断点恢复…");
                intent = ChatIntent.valueOf(String.valueOf(breakpoint.get("intent")));
                composed = (String) breakpoint.get("composed");
                sessionHits = castSessionHits(breakpoint.get("sessionHits"));
                profileContext = (String) breakpoint.get("profileContext");
                historySection = (String) breakpoint.get("historySection");
                inputTokens = breakpoint.get("inputTokens") == null
                        ? 0 : ((Number) breakpoint.get("inputTokens")).intValue();
                candidates = (String) breakpoint.get("candidates");
            } else {
                l.onThinking("preference", "正在分析您的偏好…");
                // M3-12：步骤 3 偏好（确定性偏好保存；语义同 F71）
                chatPreferenceStep.saveIfPreference(userId, message);
                l.onThinking("knowledge", "正在整理会话知识…");
                // M3-13：步骤 4 知识（切片+异步写入；语义同 Phase C/F78 C1）
                chatKnowledgeStep.writeUserMessageAsync(sessionId, message);
                l.onThinking("intent", "正在理解您的意图…");
                // M3-14：步骤 5 意图（分类+追溯填充；语义同 F85/F89）
                intent = chatIntentStep.classify(sessionId, userId, message);

                l.onThinking("memory", "正在回顾会话记忆…");
                // M3-15：步骤 6 记忆（画像+历史/摘要组装；语义同 F50/F55/F57/F60）
                ChatMemoryStep.MemoryContext memory = chatMemoryStep.assemble(userId, sessionId);
                profileContext = memory.profileContext();
                historySection = memory.historySection();
                boolean summaryUsed = memory.summaryUsed();
                boolean summaryTriggered = memory.summaryTriggered();
                int turns = memory.turns();
                int totalHistoryTokens = memory.totalHistoryTokens();
                l.onThinking("budget", "正在组装上下文…");
                // M3-16：步骤 7 预算（检索注入+组装+四档预算兜底；语义同 F63/F66/F78/F83/F85）
                ChatBudgetStep.BudgetContext budget = chatBudgetStep.compose(sessionId, userId, intent,
                        message, profileContext, historySection);
                composed = budget.composed();
                inputTokens = budget.inputTokens();
                profileContext = budget.profileContext();
                historySection = budget.historySection();
                candidates = budget.candidates();
                sessionHits = budget.sessionHits();

                log.info("聊天输入组装完成: 总长度={}, 含画像={}, 含历史={}, 含摘要={}, 摘要触发={}, 历史轮数={}, 全量历史token={}, 注入token={}, 含知识库候选={}",
                        composed.length(), !profileContext.isBlank(), !historySection.isBlank(),
                        summaryUsed, summaryTriggered, turns, totalHistoryTokens, inputTokens,
                        !"[]".equals(candidates));

                // M6-36：进入路由前保存断点快照（中断后可从此处恢复）
                Map<String, Object> snapshot = new LinkedHashMap<>();
                snapshot.put("intent", intent.name());
                snapshot.put("composed", composed);
                snapshot.put("sessionHits", sessionHits);
                snapshot.put("profileContext", profileContext);
                snapshot.put("historySection", historySection);
                snapshot.put("inputTokens", inputTokens);
                snapshot.put("candidates", candidates);
                breakpointStore.saveBreakpoint(sessionId, clientMessageId, snapshot);
            }

            // M6-36/40：路由前检查中断（Redis 标记 + 本地取消令牌）
            if (breakpointStore.isInterrupted(clientMessageId) || cancellation.isCancelled()) {
                throw new TurnInterruptedException("轮次已中断");
            }

            l.onThinking("routing", "正在生成回答…");
            // M3-17/M6：步骤 8 路由（意图分派 recall/direct/supervisor；语义同 F85/F64/F27）
            // M6：JSON 路径（NOOP listener）保持阻塞式 route() 行为逐字等价；
            // 流式路径走 routeStream()——直答/回顾真 token 流，规划分块流。
            ChatRoutingStep.StreamRouteResult routed;
            if (l == ChatProgressListener.NOOP) {
                ChatRoutingStep.RouteResult blocking =
                        chatRoutingStep.route(intent, composed, userId, sessionId,
                                sessionHits, cancellation);
                routed = new ChatRoutingStep.StreamRouteResult(blocking.response(),
                        blocking.aiTokens(), blocking.fallback(), false);
            } else {
                routed = chatRoutingStep.routeStream(intent, composed, userId, sessionId,
                        sessionHits, cancellation, l::onToken, l::onThinking);
            }
            String response = routed.response();
            long aiTokens = routed.aiTokens();

            // M6-36/40：路由后、落库前再次检查中断（在途 LLM 已消耗，但不再落库）
            if (breakpointStore.isInterrupted(clientMessageId) || cancellation.isCancelled()) {
                throw new TurnInterruptedException("轮次已中断");
            }

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
        } catch (TurnInterruptedException e) {
            // M6-36/46：中断终止——不落库 assistant 回答。
            // 幂等状态：PENDING→INTERRUPTED（用户停止可恢复；覆盖 SSE abort 与
            // interruptTurn 的竞态——谁先到都收敛为 INTERRUPTED，刷新后可重试）；
            // 已是 FAILED（新消息终止在途 markSessionInterrupted 先行）保持不变，
            // 保证"重试按钮永久消失"语义。
            chatPersistenceStep.markTurnInterrupted(sessionId, clientMessageId);
            log.warn("[ChatInterrupt] 轮次已中断，跳过落库: key={}", clientMessageId);
            throw e;
        } catch (Exception e) {
            // M7-8：轮次取消（前端停止/断连）会中断 boundedElastic 工作线程，Lettuce
            // Redis 命令随即抛 RedisCommandInterruptedException。这是“预期取消”而非
            // 业务失败：恢复中断标记并按 TurnInterruptedException 处理（置 INTERRUPTED、
            // 不落库、WARN），避免被误判 FAILED 或产生误导性 ERROR。
            if (isInterruptedCause(e)) {
                Thread.currentThread().interrupt();
                // 注意：本 catch 内抛出的异常不会再被上方 catch(TurnInterruptedException)
                // 捕获，因此在这里直接完成中断登记与日志
                chatPersistenceStep.markTurnInterrupted(sessionId, clientMessageId);
                log.warn("[ChatInterrupt] Redis 命令被线程中断，按轮次取消处理: sessionId={}, key={}",
                        sessionId, clientMessageId);
                throw new TurnInterruptedException("Redis 命令被中断（轮次取消）");
            }
            // M8-9h/M8-9i/M8-9m：模型额度不足（如 DashScope 403 Free quota
            // exhausted）——统一转为明确业务码 40303，SSE/JSON 前端可展示“模型额度
            // 不足”提示而非原始 403；覆盖三种形态：原始 403（cause 链）、
            // ChatRoutingStep 包装的 40303、QuotaShortCircuitInterceptor 短路抛出的
            // 无 cause 40303。
            if (isQuotaFailure(e)) {
                log.warn("[ChatStream] 模型额度不足: sessionId={}, key={}", sessionId, clientMessageId);
                chatPersistenceStep.failTurn(clientMessageId);
                throw new BusinessException(ErrorCode.MODEL_QUOTA_EXCEEDED.code(),
                        buildModelQuotaMessage(prepared.model()), e);
            }
            // M4-3（复核观察项 2）：步骤 3~9 意外异常时幂等记录置 FAILED——
            // 否则 PENDING 悬挂，同键重试永远 40904（failTurn 对空键/开关关为 no-op）
            // M7-8：必须打 ERROR——ChatStreamService 会把该异常转成 SSE error 事件
            // 且不打日志，若此处静默则“组装完成但无路由/落库”的故障无法排查
            log.error("[ChatStream] 执行异常: sessionId={}, key={}", sessionId, clientMessageId, e);
            chatPersistenceStep.failTurn(clientMessageId);
            throw e;
        } finally {
            cancellationRegistry.remove(clientMessageId);
        }
    }

    /** 异常链中是否存在 InterruptedException（含 Lettuce RedisCommandInterruptedException）。 */
    private static boolean isInterruptedCause(Throwable e) {
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 16) {
            if (cur instanceof InterruptedException
                    || cur instanceof RedisCommandInterruptedException) {
                return true;
            }
            cur = cur.getCause();
            depth++;
        }
        return false;
    }

    /**
     * M8-9h/M8-9i：识别模型额度不足异常。
     * 实现收敛到 {@link ModelQuotaExceptionSupport}，供 ChatRoutingStep 与
     * ChatService 共用（此处保留薄封装以兼容既有测试与调用点）。
     */
    static boolean isModelQuotaExceeded(Throwable e) {
        return ModelQuotaExceptionSupport.isModelQuotaExceeded(e);
    }

    /** M8-9m：统一额度失败判定（含短路场景的无 cause 40303）。 */
    static boolean isQuotaFailure(Throwable e) {
        return ModelQuotaExceptionSupport.isQuotaFailure(e);
    }

    /**
     * M8-9h：组装模型额度不足提示——动态携带模型名，前端无需为每个模型写死文案；
     * 模型名未知时退回通用错误文案。
     */
    static String buildModelQuotaMessage(String model) {
        if (model != null && !model.isBlank()) {
            return "模型 " + model + " 额度不足：请切换其他模型，或在控制台充值/关闭“仅免费额度”后重试";
        }
        return ErrorCode.MODEL_QUOTA_EXCEEDED.message();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castSessionHits(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                Map<String, Object> map = new LinkedHashMap<>();
                m.forEach((k, v) -> map.put(String.valueOf(k), v));
                result.add(map);
            }
        }
        return result;
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
        ChatSession session = requireOwnedSession(userId, sessionId);
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
        ChatSession session = requireOwnedSession(userId, sessionId);
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

    /**
     * M6-56/T4：会话存在 + 归属校验收敛（40101 / 40404 / 40302）。
     */
    private ChatSession requireOwnedSession(Long userId, String sessionId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(40101, "用户未登录");
        }
        ChatSession session = chatPersistenceStep.requireSession(sessionId);
        if (!userId.equals(session.getUserId())) {
            throw new BusinessException(40302, "无权访问该会话");
        }
        return session;
    }
}
