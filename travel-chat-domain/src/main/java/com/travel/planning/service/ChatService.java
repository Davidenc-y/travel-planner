package com.travel.planning.service;

import com.travel.common.dto.ChatResponseDTO;
import com.travel.common.entity.ChatMessage;
import com.travel.common.entity.ChatMessageIdem;
import com.travel.common.entity.ChatSession;
import com.travel.common.exception.BusinessException;
import com.travel.common.exception.ErrorCode;
import com.travel.common.enums.SessionStatus;
import com.travel.core.stream.TurnGate;
import com.travel.aigateway.core.ModelRegistry;
import com.travel.aigateway.route.ModelRoutingContext;
import com.travel.planning.cancellation.TurnCancellationBroadcaster;
import com.travel.planning.cancellation.TurnState;
import com.travel.planning.cancellation.TurnStateMachine;
import com.travel.stream.service.ChatProgressListener;
import com.travel.stream.service.TurnCancellation;
import com.travel.stream.service.TurnInterruptedException;
import com.travel.stream.service.ChatStreamExecutor;
import com.travel.stream.service.TurnCancellationRegistry;
import com.travel.common.config.ChatIntent;
import com.travel.memory.sessionstore.SessionStorePort;
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
import com.travel.memory.shortterm.SessionFinalizer;
import com.travel.planning.trace.ModelRouteTracker;
import com.travel.planning.trace.TtftChannel;
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
    // M7：实际路由模型追溯记录（direct 路径由 runStream 包裹捕获；HC-5 起为 TraceGateway 缺省时的降级直连）
    private final ModelRouteTracker modelRouteTracker;
    /** M23（E1）：锚定存储（brief 渲染 + 会话锚定集合）——MM-1a.4 收编改走记忆门面。 */
    private final com.travel.memory.MemoryFacade memoryFacade;
    private final com.travel.memory.anchor.ItineraryBriefPort itineraryBriefPort;

    /** T-5c：恢复注入开关（默认关=E-33 纯净；审计相灰度开启） */
    @org.springframework.beans.factory.annotation.Value(
            "${travel.chat.supervisor.resume-ledger.enabled:false}")
    private boolean resumeLedgerEnabled = false;

    /** T-5c：子代理结果台账（optional 注入，null=零决策零注入） */
    private com.travel.planning.memory.knowledge.SupervisorResultLedger supervisorResultLedger;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSupervisorResultLedger(
            com.travel.planning.memory.knowledge.SupervisorResultLedger supervisorResultLedger) {
        this.supervisorResultLedger = supervisorResultLedger;
    }
    /** M23b（E4）：偏好段渲染器（确定性；含目的地冲突提示行）。 */
    private final com.travel.planning.memory.preference.PreferenceSectionRenderer preferenceSectionRenderer;
    /** M23b（E3）：注意力焦点判定器（观测模式：仅日志，不隔离）。 */
    private final com.travel.planning.memory.focus.AttentionFocusResolver attentionFocusResolver;
    /** M24（E3）：DETOUR 词表匹配器（意图无关预判，供写通道闸门）。 */
    private final com.travel.planning.memory.focus.DetourWordMatcher detourWordMatcher;
    /** M24（E3）：三闸门隔离配置（默认关；D-V8-4 观测期达标后开启）。 */
    private final com.travel.planning.config.DetourIsolationProperties detourIsolationProperties;
    /** R4.1：偏好观测日志摘要（preferenceSummary 方法体原样迁出至 ChatPreferenceLogSupport，文案零变更；行内初始化保持既有直构签名不变）。 */
    private final ChatPreferenceLogSupport chatPreferenceLogSupport = new ChatPreferenceLogSupport();
    /** R4.2：锚定策略（自动锚定判定+锚定持久化薄封装迁出至 ChatAnchorPolicy；行内初始化保持既有直构签名不变）。 */
    private final ChatAnchorPolicy chatAnchorPolicy = new ChatAnchorPolicy();
    /** Z-4a：轮次生命周期（中断/清除断点/状态/最近可恢复轮次迁出至 TurnLifecycleService；末位字段=委托构造参数追加在尾部，既有构造调用点位置零移）。 */
    private final TurnLifecycleService turnLifecycleService;
    /** Z-4b：会话查询/生命周期（建会话/历史/关会话迁出至 ChatSessionQueryService；继续尾部追加）。 */
    private final ChatSessionQueryService chatSessionQueryService;
    /** MI-1：标题策略（首条消息标题联动+标题生成/更新迁出至 ChatTurnTitlePolicy；行内初始化保持既有直构签名不变）。 */
    private final ChatTurnTitlePolicy chatTurnTitlePolicy = new ChatTurnTitlePolicy();
    /** MI-1：门禁装配（ARCHIVED 拒写/追加决策/断点清理+在途终止/模型校验迁出至 ChatGateSupport；行内初始化保持既有直构签名不变）。 */
    private final ChatGateSupport chatGateSupport = new ChatGateSupport();
    /** MI-4：trace 门面（可选注入；缺省时 recordRoutedModel 降级为 modelRouteTracker 直连——观测通道不阻断业务）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.travel.planning.trace.TraceGateway traceGateway;
    /** MI-4：轮次取消链状态机（可选注入；缺省时跳过接线断言）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.travel.planning.cancellation.TurnStateMachine stateMachine;

    /** AB-5c：时段画像采集开关（默认关=E-33 关闭态零调用零 DB 写；审计实弹开启）。 */
    @org.springframework.beans.factory.annotation.Value(
            "${travel.profile.slot-enabled:false}")
    private boolean profileSlotEnabled = false;

    /** AB-5c：画像结构化端口（optional 注入；null 或开关关=零采集）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.travel.memory.longterm.ProfileSlotPort profileSlotPort;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setProfileSlotPort(com.travel.memory.longterm.ProfileSlotPort profileSlotPort) {
        this.profileSlotPort = profileSlotPort;
    }

    /** AB-5c：测试直连（同包；生产装配走 @Value 字段注入）。 */
    void setProfileSlotCollector(boolean enabled,
                                 com.travel.memory.longterm.ProfileSlotPort port) {
        this.profileSlotEnabled = enabled;
        this.profileSlotPort = port;
    }

    /**
     * 创建会话
     * <p>Z-4b：方法体迁出至 {@link ChatSessionQueryService}（公共 API 签名零变）。</p>
     */
    public String createSession(Long userId, String title) {
        return chatSessionQueryService.createSession(userId, title);
    }

    /**
     * 获取会话历史
     */
    /** M21-2 SEC-02-03 止血：历史读取前先校验会话归属（40404 不存在 / 40302 非本人）。
     * <p>Z-4b：方法体迁出至 {@link ChatSessionQueryService}（公共 API 签名零变）。</p> */
    public List<ChatMessage> getHistory(Long userId, String sessionId) {
        return chatSessionQueryService.getHistory(userId, sessionId);
    }

    /**
     * 发送消息并获取响应（无幂等键重载，原路径）。
     */
    /** Z-4c：旧签名收敛（@Deprecated 委托一版；公共 API 零删除）。 */
    @Deprecated
    public ChatResponseDTO sendMessage(String sessionId, String message, Long userId) {
        return sendMessage(sessionId, message, userId, null);
    }

    /**
     * 发送消息并获取响应（M4-3：支持消息级幂等）。
     *
     * <p>幂等语义见 {@link ChatPersistenceStep#beginTurn}：COMPLETED 重放 /
     * PENDING 40904 / FAILED 复用重跑 / 未命中同事务登记；兜底文案登记 FAILED
     * （重试重新执行，不重放兜底，M4-0-R1 评审 D3-1/D3-2）。</p>
     * @deprecated Z-4c 收敛至 {@link #sendMessage(ChatTurnRequest)}
     */
    @Deprecated
    public ChatResponseDTO sendMessage(String sessionId, String message, Long userId, String clientMessageId) {
        return sendMessage(sessionId, message, userId, clientMessageId, null);
    }

    /**
     * 发送消息并获取响应（M4-3 幂等 + M7 请求级模型）。
     * @deprecated Z-4c 收敛至 {@link #sendMessage(ChatTurnRequest)}
     */
    @Deprecated
    public ChatResponseDTO sendMessage(String sessionId, String message, Long userId,
                                       String clientMessageId, String model) {
        return sendMessage(sessionId, message, userId, clientMessageId, model, java.util.List.of());
    }

    /** M23（E1）：JSON 兜底路径同样携带锚定快照（与 SSE 路径 per-turn truth 一致）。
     * @deprecated Z-4c 收敛至 {@link #sendMessage(ChatTurnRequest)} */
    @Deprecated
    public ChatResponseDTO sendMessage(String sessionId, String message, Long userId,
                                       String clientMessageId, String model,
                                       java.util.List<Long> anchorIds) {
        return sendMessage(ChatTurnRequest.of(userId, sessionId, message,
                clientMessageId, model, anchorIds));
    }

    /** Z-4c：sendMessage 主方法（参数对象收敛；真实体自六参版逐字搬运，参数访问机械替换）。 */
    public ChatResponseDTO sendMessage(ChatTurnRequest req) {
        String sessionId = req.sessionId();
        ChatStreamExecutor.ChatStreamPrepared prepared =
                prepareStream(req);
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
                // T-2：JSON 路径 itineraryId 透传（规划/REFINE 回写成功轮非空；replay 分支不填=null 保持）
                .itineraryId(result.itineraryId())
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
        // M23（E1）：旧五参委托六参（锚定空集）——既有调用点零破坏
        return prepareStream(ChatTurnRequest.of(userId, sessionId, message, clientMessageId, model, java.util.List.of()));
    }

    /** M23（E1）：六参实现委托七参（无偏好）——既有调用点零破坏。 */
    @Override
    public ChatStreamExecutor.ChatStreamPrepared prepareStream(
            Long userId, String sessionId, String message, String clientMessageId, String model,
            java.util.List<Long> anchorIds) {
        return prepareStream(ChatTurnRequest.of(userId, sessionId, message, clientMessageId, model, anchorIds));
    }

    /**
     * M23b/M28-17：七参实现——偏好标签经 prepared 贯穿 runStream。
     *
     * <p>M28-17 定案：此前 ChatService 只实现六参，接口的七参 default 方法
     * 委托六参时<b>静默丢弃 preferences</b>，prepared.preferences() 恒 null——
     * 两端控制器（MVC/webflux，M28-12）的透传全部白接，图流输入永远没有
     * 【本轮偏好约束】段（2026-09-08 浏览器抓包 body 携带 preferences 而
     * 日志"(未携带)"的实证断点）。</p>
     */
    @Override
    public ChatStreamExecutor.ChatStreamPrepared prepareStream(
            Long userId, String sessionId, String message, String clientMessageId, String model,
            java.util.List<Long> anchorIds,
            com.travel.common.dto.PreferenceTagsDTO preferences) {
        return prepareStream(new ChatTurnRequest(userId, sessionId, message,
                clientMessageId, model, anchorIds, preferences));
    }

    /**
     * Z-4c：prepareStream 主方法（参数对象收敛；真实体自七参版逐字搬运，参数访问机械替换）。
     *
     * <p>M23b/M28-17：偏好标签经 prepared 贯穿 runStream——M28-17 定案：此前 preferences
     * 被静默丢弃致图流输入永远没有【本轮偏好约束】段，本主方法保持七参语义单点。</p>
     */
    public ChatStreamExecutor.ChatStreamPrepared prepareStream(ChatTurnRequest req) {
        java.util.List<Long> anchorIds = req.anchorIds() == null
                ? java.util.List.of() : req.anchorIds();
        // F52：防御脏 userId（兜底 0 会导致 user_id=0 画像/会话）。
        if (req.userId() == null || req.userId() <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED.code(), ErrorCode.UNAUTHORIZED.message());
        }
        // M7 D6：请求级 model 必须在注册表且 selectable，否则入口快速失败
        chatGateSupport.validateModel(modelRegistry, req.model());
        // F90：调用前安全防护（Prompt 注入检测）→ MessagePipeline 步骤 1
        chatGuardStep.check(req.userId(), req.message());
        // M3-11：步骤 2 持久化（会话校验 + 用户消息落库）
        ChatSession session = requireOwnedSession(req.userId(), req.sessionId());
        // AB-5c：时段画像采集——sendMessage/prepareStream 全重载汇于本方法（每轮有效入口恰好一次，
        // 重放/重复发送亦为该时段真实用户动作）；E-33：travel.profile.slot-enabled 默认 false=零调用
        // 零 DB 写；fail-open（采集异常不阻断主流程，同画像更新降级口径）
        if (profileSlotEnabled && profileSlotPort != null) {
            try {
                profileSlotPort.upsertSlotUsage(req.userId(),
                        slotOf(java.time.LocalDateTime.now()));
            } catch (Exception e) {
                log.warn("[ProfileSlot] 时段画像采集失败（不影响主流程）: userId={}, err={}",
                        req.userId(), e.getMessage());
            }
        }
        // M4-3：幂等门禁（在用户消息落库之前；未命中时用户消息已在门禁事务内追加）
        TurnGate gate = chatPersistenceStep.beginTurn(
                req.sessionId(), req.userId(), req.clientMessageId(), req.message());
        String updatedSessionTitle = null;
        if (gate.proceed()) {
            chatGateSupport.enforceTurnEntry(sessionGuardProps, session, gate,
                    chatPersistenceStep, req.sessionId(), req.message());
            updatedSessionTitle = chatTurnTitlePolicy.firstMessageTitle(
                    sessionStorePort, titleProps, req.sessionId(), req.message());
            chatGateSupport.clearBreakpointsAndTerminate(gate, breakpointStore, chatPersistenceStep,
                    cancellationRegistry, cancellationBroadcaster, req.sessionId(), req.clientMessageId(),
                    resumeLedgerEnabled ? supervisorResultLedger : null);
        }
        return new ChatStreamExecutor.ChatStreamPrepared(
                req.sessionId(), req.message(), req.userId(), req.clientMessageId(), gate, updatedSessionTitle, req.model(),
                anchorIds, req.preferences());
    }

    /**
     * AB-5c：时段编号计算（hour/4 → 0~5 共 6 段，与 t_user_profile_slot.slot_id 口径一致）。
     * package-static 供测试直连（V-1 recordBlockingTtft 先例）。
     */
    static int slotOf(java.time.LocalDateTime t) {
        return t.getHour() / 4;
    }

    /**
     * M6-36：中断在途轮次（PENDING → FAILED + Redis 中断标记）。
     *
     * <p>断点快照由 runStream 在路由前写入；若中断发生时尚未写入（步骤 3~7），
     * 重试将按 FAILED 语义整体重跑。</p>
     * <p>Z-4a：方法体迁出至 {@link TurnLifecycleService}（公共 API 签名零变）。</p>
     */
    public void interruptTurn(Long userId, String sessionId, String clientMessageId) {
        turnLifecycleService.interruptTurn(userId, sessionId, clientMessageId);
    }

    /**
     * M6-36：清除断点（用户发新消息时前端调用；prepareStream 侧另有双保险）。
     * <p>Z-4a：方法体迁出至 {@link TurnLifecycleService}（公共 API 签名零变）。</p>
     */
    public void clearBreakpoint(Long userId, String sessionId, String clientMessageId) {
        turnLifecycleService.clearBreakpoint(userId, sessionId, clientMessageId);
    }

    /**
     * M6-42：查询轮次状态（前端刷新后恢复重试入口）。
     *
     * <p>resumable = INTERRUPTED 且断点快照仍存在（Redis 30min TTL 窗口内）；
     * 新消息已把旧轮次置 FAILED，故返回 FAILED 时前端不显示重试。</p>
     * <p>Z-4a：方法体迁出至 {@link TurnLifecycleService}（结果 record 留守=公共 FQN 零变）。</p>
     */
    public TurnStatusResult getTurnStatus(Long userId, String sessionId, String clientMessageId) {
        return turnLifecycleService.getTurnStatus(userId, sessionId, clientMessageId);
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
     * <p>Z-4a：方法体迁出至 {@link TurnLifecycleService}（结果 record 留守=公共 FQN 零变）。</p>
     */
    public LatestInterruptedTurn getLatestInterruptedTurn(Long userId, String sessionId) {
        return turnLifecycleService.getLatestInterruptedTurn(userId, sessionId);
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
                ChatStreamExecutor.ChatStreamResult result = runStreamInternal(prepared, listener);
                // AB-5c-2：模型×时段使用采集（成功轮；异常轮无 tokens 不计）——守卫同 5c-1，fail-open
                collectModelUsage(prepared, result);
                return result;
            } finally {
                // M8-9j：异常（如额度 403）也必须记录实际路由模型——
                // 否则 t_agent_trace.model_name 停留在默认值，无法区分“请求未带模型”
                // 与“带了模型但路由失败”
                recordRoutedModel();
            }
        });
    }

    /**
     * AB-5c-2：模型×时段使用采集（runStream 返回侧=JSON/SSE 两路径公共完成点——
     * runStreamInternal 阻塞至图完成，SSE 仅经 listener 旁路推送）。E-33：flag 默认关=
     * 零调用零 DB 写；fail-open 不影响主流程。模型 key 取实际路由值（ModelRoutingContext
     * .routed()，本方法仍在 runWith 作用域内=ThreadLocal 未清），无路由回落请求级模型，
     * 双空则不计。ttft 经 TtftChannel.take(requestId) 尽力取值（TraceContext 未激活=null
     * 容忍，Port 侧 CASE WHEN 不触碰均值）。package 供测试直连（V-1 先例）。
     */
    void collectModelUsage(ChatStreamExecutor.ChatStreamPrepared prepared,
                           ChatStreamExecutor.ChatStreamResult result) {
        if (!profileSlotEnabled || profileSlotPort == null) {
            return;
        }
        try {
            String routed = ModelRoutingContext.routed();
            String modelKey = routed != null && !routed.isBlank() ? routed : prepared.model();
            if (modelKey == null || modelKey.isBlank()) {
                return;
            }
            Long ttftRaw = TraceContext.active()
                    ? TtftChannel.take(TraceContext.current().requestId) : null;
            Integer ttftMs = ttftRaw == null ? null : ttftRaw.intValue();
            profileSlotPort.incrementModelUsage(prepared.userId(), modelKey,
                    slotOf(java.time.LocalDateTime.now()),
                    result == null ? 0L : result.aiTokens(), ttftMs);
        } catch (Exception e) {
            log.warn("[ProfileSlot] 模型使用采集失败（不影响主流程）: userId={}, err={}",
                    prepared.userId(), e.getMessage());
        }
    }

    /** MI-4：取消链状态接线断言（非法迁移抛 IllegalStateException → 上报；可选注入，缺省跳过）。 */
    private void assertTurnTransition(TurnState from, TurnState to) {
        if (stateMachine != null) {
            stateMachine.assertTransition(from, to);
        }
    }

    /** M7：direct 路径在同一线程完成路由，把实际模型写入追溯（图流路径由拦截器记录）。
     *  MI-4：本方法收编至 trace/TraceGateway（本类保留可选委托调用，缺省时降级 modelRouteTracker 直连——观测通道不阻断业务）。 */
    private void recordRoutedModel() {
        if (traceGateway != null) {
            traceGateway.recordRoutedModel();
            return;
        }
        String routed = ModelRoutingContext.routed();
        if (routed == null || !TraceContext.active()) {
            return;
        }
        modelRouteTracker.record(TraceContext.current().requestId, routed);
    }

    /** T-5c：三路决策结果（decision=seeded|full-rerun|stale-skipped；seed 仅 seeded 非空）。 */
    record ResumeDecision(String decision, java.util.Map<String, String> seed) {
    }

    /**
     * T-5c：三路决策纯函数——断点不在=stale-skipped（非重试轮，不注入）；
     * 断点在+台账空=full-rerun（冻结上下文整图重跑=现状行为）；断点在+台账非空=seeded。
     * 包级 static=单测直连。
     */
    static ResumeDecision decideResume(boolean breakpointPresent,
                                       java.util.Map<String, String> loaded) {
        if (!breakpointPresent) {
            return new ResumeDecision("stale-skipped", null);
        }
        if (loaded == null || loaded.isEmpty()) {
            return new ResumeDecision("full-rerun", null);
        }
        return new ResumeDecision("seeded", loaded);
    }

    /**
     * T-5c：composed 插入【已完成的子任务结果（中断前复用）】段——位置强制
     * 【当前问题】标记之前（冲突①：标记之后会被 ExplicitInputParser lastIndexOf
     * 尾段解析吞入，污染 destination/days 确定性解析）；每键一行=文本前 100 字摘要
     * （完整文本在 state 种子里，摘要仅供主代理路由参考）；marker 缺失则原样返回
     * （不注入，仅 state 种子兜底——段为软引导）。public static=planning 回归测试直连。
     */
    public static String insertResumeSection(String composed,
                                             java.util.Map<String, String> resumeSeed) {
        if (composed == null || resumeSeed == null || resumeSeed.isEmpty()) {
            return composed;
        }
        StringBuilder section = new StringBuilder("\n【已完成的子任务结果（中断前复用）】\n");
        resumeSeed.forEach((key, text) -> {
            String t = text == null ? "" : text.trim();
            section.append(key).append('=')
                    .append(t.length() > 100 ? t.substring(0, 100) : t)
                    .append('\n');
        });
        int qIdx = composed.indexOf(com.travel.common.prompt.Markers.CURRENT_QUESTION);
        if (qIdx < 0) {
            return composed;
        }
        return composed.substring(0, qIdx) + section + composed.substring(qIdx);
    }

    private ChatStreamExecutor.ChatStreamResult runStreamInternal(
            ChatStreamExecutor.ChatStreamPrepared prepared, ChatProgressListener listener) {
        // M23（E1）：消息内锚定快照（per-turn truth；切换/勾选随消息生效）
        java.util.List<Long> anchorIds = prepared.anchorIds() == null
                ? java.util.List.of() : prepared.anchorIds();
        // M25（E4 收尾）：偏好冲突信号（gate 块内赋值；方法级声明供 Result 组装）
        com.travel.stream.service.ChatStreamExecutor.ChatStreamResult.PreferenceConflict preferenceConflict = null;
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
        // MI-4：取消链状态接线——注册=RUNNING（本轮起点，无前驱状态不设断言）
        TurnState turnState = TurnState.RUNNING;
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
                // M24（E3）：DETOUR 闸门（默认关）——detour 轮跳过偏好落库/会话切片，
                // 主线记忆不被偶然偏移污染（闸门 1/2；观察日志见 [ChatFocus]）
                boolean detourSkip = detourIsolationProperties.isEnabled()
                        && detourWordMatcher.isLikelyDetour(message);
                if (detourSkip) {
                    log.info("[ChatFocus] DETOUR 隔离生效: sessionId={}, profileSkip={}, sliceSkip={}",
                            sessionId, detourIsolationProperties.isProfileSkip(),
                            detourIsolationProperties.isSliceSkip());
                }
                // M27（S5/E3 观测支撑）：隔离生效标记进 trace（callPath 编码；开关关时不记录）
                if (detourSkip) {
                    com.travel.planning.trace.TraceContext.Holder traceHolder =
                            com.travel.planning.trace.TraceContext.current();
                    if (traceHolder != null) {
                        traceHolder.detourSkipped = true;
                    }
                }
                // M3-12：步骤 3 偏好（确定性偏好保存；语义同 F71）
                if (!(detourSkip && detourIsolationProperties.isProfileSkip())) {
                    chatPreferenceStep.saveIfPreference(userId, message);
                }
                l.onThinking("knowledge", "正在整理会话知识…");
                // M3-13：步骤 4 知识（切片+异步写入；语义同 Phase C/F78 C1）
                if (!(detourSkip && detourIsolationProperties.isSliceSkip())) {
                    chatKnowledgeStep.writeUserMessageAsync(sessionId, message);
                }
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
                // M23b（E3，P-F 第一阶段）：焦点判定（观测模式——仅结构化日志，三闸门隔离待观测期达标）
                var focus = attentionFocusResolver.resolve(intent, message);
                log.info("[ChatFocus] sessionId={}, intent={}, focus={}, anchorCount={}",
                        sessionId, intent, focus, anchorIds.size());
                // M27（S5/E3 观测支撑）：焦点判定进 trace（callPath 编码，供达标检查脚本/看板量化）
                com.travel.planning.trace.TraceContext.Holder traceHolder =
                        com.travel.planning.trace.TraceContext.current();
                if (traceHolder != null) {
                    traceHolder.focusKind = focus.name();
                }
                // M23（E1）：锚定段渲染（空集→空段不注入）；切片过滤在 BudgetStep 内按 anchorIds 执行
                // M28-13：消息携带锚定（含新会话首条消息的预选草稿）→ 持久化为服务端锚定
                // （用户显式选择=持续意图；幂等——与现值一致时无额外写放大）
                if (!anchorIds.isEmpty()) {
                    chatAnchorPolicy.persistAnchors(memoryFacade, userId, sessionId, anchorIds);
                }
                String anchorSection = memoryFacade.renderAnchorSection(userId, anchorIds);
                // M23b（E4）：偏好段渲染（含与锚定目的地的冲突提示行）+ 检索 query 偏好拼接
                com.travel.common.dto.PreferenceTagsDTO preferences = prepared.preferences();
                // M28-14：偏好标签到达性观测（一条 INFO 即可判定前端是否携带——
                // 2026-09-08 19:49/19:52 实测"无偏好段"取证曾需反复排查）
                log.info("[ChatPreference] 本轮偏好标签: {}", chatPreferenceLogSupport.preferenceSummary(preferences));
                String preferenceSection = preferenceSectionRenderer.render(
                        preferences,
                        anchorBriefsDestination(anchorSection));
                // M25（E4 收尾）："记住为长期偏好"——用户显式勾选才落画像（合并语义）
                if (preferences != null && Boolean.TRUE.equals(preferences.getRemember())) {
                    chatPreferenceStep.saveStructuredTags(userId, preferences);
                }
                // M25（E4 收尾）：目的地冲突确定性信号（渲染器已附提示行；此处结构化供前端卡片）
                preferenceConflict = preferenceConflictOf(preferences, anchorSection);
                boolean anchorBasedConflict = preferenceConflict != null;
                // M28-4：无锚定时的回退比对——偏好目的地 vs 会话最新行程目的地
                // （用户手动改城市后的可见性；仅卡片信号，不注入口径 guidance——
                //  无锚定场景本轮实际沿用会话行程，"按偏好处理"口径不成立）
                if (preferenceConflict == null && preferences != null
                        && preferences.getDestination() != null
                        && !preferences.getDestination().isBlank()) {
                    preferenceConflict = conflictAgainstSessionItinerary(
                            userId, sessionId, preferences.getDestination());
                }
                ChatBudgetStep.BudgetContext budget = chatBudgetStep.compose(sessionId, userId, intent,
                        message, profileContext, historySection, anchorSection, anchorIds,
                        preferenceSection, preferenceSectionRenderer.querySuffix(preferences));
                composed = budget.composed();
                inputTokens = budget.inputTokens();
                profileContext = budget.profileContext();
                historySection = budget.historySection();
                candidates = budget.candidates();
                sessionHits = budget.sessionHits();

                // M28-2：偏好-锚定冲突的"回答可见性"——冲突且本轮是规划/改规划时，
                // 注入确定性口径说明指令，要求回答开头向用户解释锚定与偏好不一致及处理依据
                // （操作受限原因/修复依据可见；CHAT/FUNCTIONAL 直答轮不注入，避免答非所问）
                if (anchorBasedConflict
                        && (intent == ChatIntent.PLANNING || intent == ChatIntent.REFINE)) {
                    composed = composed + "\n" + conflictAnswerGuidance(
                            preferenceConflict.preferredDestination(),
                            preferenceConflict.anchoredDestination());
                }

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
                // MI-4：取消链状态接线——收到广播=CANCEL_REQUESTED
                assertTurnTransition(turnState, TurnState.CANCEL_REQUESTED);
                turnState = TurnState.CANCEL_REQUESTED;
                throw new TurnInterruptedException("轮次已中断");
            }

            l.onThinking("routing", "正在生成回答…");
            // T-5c：恢复注入三路决策（台账仅流式路径捕获，种子仅流式路径消费——
            // NOOP 阻塞路径按设计 full-rerun，见 SupervisorGraphExecutor 降级注明）
            java.util.Map<String, String> resumeSeed = null;
            if (resumeLedgerEnabled && supervisorResultLedger != null
                    && l != ChatProgressListener.NOOP) {
                ResumeDecision decision = decideResume(
                        !breakpointStore.loadBreakpoint(sessionId, clientMessageId).isEmpty(),
                        supervisorResultLedger.loadAll(sessionId, clientMessageId));
                if ("seeded".equals(decision.decision())) {
                    resumeSeed = decision.seed();
                    log.info("[ResumeLedger] decision=seeded keys={} sessionId={}",
                            resumeSeed.keySet(), sessionId);
                } else if ("full-rerun".equals(decision.decision())) {
                    log.info("[ResumeLedger] decision=full-rerun reason=断点在台账空（整图重跑=现状行为）: sessionId={}",
                            sessionId);
                } else {
                    log.debug("[ResumeLedger] decision=stale-skipped reason=断点不在（非重试轮）: sessionId={}",
                            sessionId);
                }
            }
            if (resumeSeed != null) {
                composed = insertResumeSection(composed, resumeSeed);
            }
            // M3-17/M6：步骤 8 路由（意图分派 recall/direct/supervisor；语义同 F85/F64/F27）
            // M6：JSON 路径（NOOP listener）保持阻塞式 route() 行为逐字等价；
            // 流式路径走 routeStream()——直答/回顾真 token 流，规划分块流。
            ChatRoutingStep.StreamRouteResult routed;
            if (l == ChatProgressListener.NOOP) {
                ChatRoutingStep.RouteResult blocking =
                        chatRoutingStep.route(intent, composed, userId, sessionId,
                                sessionHits, cancellation);
                // T-2：5 参构造透传 writtenItineraryId（原 4 参兼容构造丢弃该 id——
                // JSON 路径 auto-anchor/preferenceSync/itineraryId 填充此前恒 null）
                routed = new ChatRoutingStep.StreamRouteResult(blocking.response(),
                        blocking.aiTokens(), blocking.fallback(), false,
                        blocking.writtenItineraryId());
            } else {
                routed = resumeSeed == null
                        ? chatRoutingStep.routeStream(intent, composed, userId, sessionId,
                                sessionHits, cancellation, l::onToken, l::onThinking)
                        : chatRoutingStep.routeStream(intent, composed, userId, sessionId,
                                sessionHits, cancellation, l::onToken, l::onThinking, resumeSeed);
            }
            String response = routed.response();
            long aiTokens = routed.aiTokens();

            // M6-36/40：路由后、落库前再次检查中断（在途 LLM 已消耗，但不再落库）
            if (breakpointStore.isInterrupted(clientMessageId) || cancellation.isCancelled()) {
                // MI-4：取消链状态接线——收到广播=CANCEL_REQUESTED
                assertTurnTransition(turnState, TurnState.CANCEL_REQUESTED);
                turnState = TurnState.CANCEL_REQUESTED;
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

            // M25（E4 收尾）：conflict 于 gate 块内计算（见 preferenceConflictOf 赋值）
            // M23（P-D）→M28-4：会话首个生成行程"自动锚定"（用户心智模型：初始规划即本会话基准；
            // 原"询问卡"在该场景冗余——已默认锚定，后续轮 getAnchors 非空不再触发。
            // 询问卡载荷/前端卡片保留为兼容死代码，不再发送）
            Long routedItineraryId = routed == null ? null : routed.writtenItineraryId();
            com.travel.stream.service.ChatStreamExecutor.ChatStreamResult.AnchorSuggestion suggestion = null;
            if (routedItineraryId != null
                    && chatAnchorPolicy.shouldAutoAnchor(anchorIds) // M28-13：用户已显式携带锚定（含新会话预选）时不被新行程覆盖
                    && memoryFacade.getAnchors(prepared.sessionId()).isEmpty()
                    && isSessionFirstItinerary(routedItineraryId,
                            itineraryBriefPort.findSessionItineraryIds(prepared.sessionId()))) {
                chatAnchorPolicy.persistAnchors(memoryFacade, userId, prepared.sessionId(),
                        java.util.List.of(routedItineraryId));
                log.info("[SessionAnchor] 会话首个行程已自动锚定: sessionId={}, itineraryId={}",
                        prepared.sessionId(), routedItineraryId);
            }
            // M26-F3：本轮有效约束回写——回写成功后从行程约束列构建（含 F1 更新后的 budget/days；
            // M28-3：含 start_date——聊天建行程/改签时从 routePlan 首日提取）
            // M28-12：含 interests（行程 interests 列）；filter 补 party/interests——
            // 此前仅有 party 时整个 sync 被过滤为 null（同行人单独回写场景标签不同步）
            com.travel.stream.service.ChatStreamExecutor.ChatStreamResult.PreferenceSync preferenceSync = null;
            if (routedItineraryId != null) {
                preferenceSync = itineraryBriefPort.briefOf(userId, routedItineraryId)
                        .map(b -> new com.travel.stream.service.ChatStreamExecutor.ChatStreamResult.PreferenceSync(
                                b.destination(), b.days(), b.budget(), b.party(),
                                b.interests() == null || b.interests().isEmpty()
                                        ? null : b.interests(),
                                b.startDate()))
                        .filter(ps -> ps.destination() != null || ps.days() != null
                                || ps.budget() != null || ps.startDate() != null
                                || ps.party() != null
                                || (ps.interests() != null && !ps.interests().isEmpty()))
                        .orElse(null);
            }
            // M28-15：done 回写载荷取证（与"本轮偏好标签"日志成对——两行即可定位
            // 断点在"前端未发"还是"回写未生成"还是"前端未消费"）
            log.info("[ChatPreference] 回写载荷: {}", preferenceSync);
            // MI-4：取消链终局接线——正常完成=COMPLETED
            assertTurnTransition(turnState, TurnState.COMPLETED);
            turnState = TurnState.COMPLETED;
            // T-2：JSON 响应 itineraryId 填充（非规划/replay 轮为 null，T-2b 消费）
            return new ChatStreamExecutor.ChatStreamResult(
                    response, aiTokens, routed.fallback(),
                    assistantMessageId, prepared.sessionTitle(),
                    suggestion, preferenceConflict, preferenceSync,
                    routedItineraryId);
        } catch (TurnInterruptedException e) {
            // M6-36/46：中断终止——不落库 assistant 回答。
            // 幂等状态：PENDING→INTERRUPTED（用户停止可恢复；覆盖 SSE abort 与
            // interruptTurn 的竞态——谁先到都收敛为 INTERRUPTED，刷新后可重试）；
            // 已是 FAILED（新消息终止在途 markSessionInterrupted 先行）保持不变，
            // 保证"重试按钮永久消失"语义。
            // MI-4：取消链终局接线——中断=CANCELLED（RUNNING 先经 CANCEL_REQUESTED，
            // 用户停止语义两步均合法；非法迁移抛 IllegalStateException → 上报）
            if (turnState == TurnState.RUNNING) {
                assertTurnTransition(turnState, TurnState.CANCEL_REQUESTED);
                turnState = TurnState.CANCEL_REQUESTED;
            }
            assertTurnTransition(turnState, TurnState.CANCELLED);
            turnState = TurnState.CANCELLED;
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
                // MI-4：取消链终局接线——中断=CANCELLED（RUNNING 先经 CANCEL_REQUESTED，
                // 用户停止语义两步均合法；非法迁移抛 IllegalStateException → 上报）
                if (turnState == TurnState.RUNNING) {
                    assertTurnTransition(turnState, TurnState.CANCEL_REQUESTED);
                    turnState = TurnState.CANCEL_REQUESTED;
                }
                assertTurnTransition(turnState, TurnState.CANCELLED);
                turnState = TurnState.CANCELLED;
                chatPersistenceStep.markTurnInterrupted(sessionId, clientMessageId);
                // M10-2d：预期取消的重复 WARN 收敛为 DEBUG（功能不受影响；
                // 同键取消/重试语义由 TurnInterruptedException + INTERRUPTED 状态负责）
                log.debug("[ChatInterrupt] Redis 命令被线程中断，按轮次取消处理: sessionId={}, key={}",
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

    /** M25（E4 收尾）：偏好目的地 vs 锚定目的地冲突（确定性；无冲突/无偏好 → null）。 */
    private com.travel.stream.service.ChatStreamExecutor.ChatStreamResult.PreferenceConflict preferenceConflictOf(
            com.travel.common.dto.PreferenceTagsDTO preferences, String anchorSection) {
        if (preferences == null || preferences.getDestination() == null || preferences.getDestination().isBlank()) {
            return null;
        }
        String anchored = anchorBriefsDestination(anchorSection);
        if (anchored == null || anchored.equals(preferences.getDestination())) {
            return null;
        }
        return new com.travel.stream.service.ChatStreamExecutor.ChatStreamResult.PreferenceConflict(
                preferences.getDestination(), anchored, "anchor");
    }

    /**
     * M28-4：无锚定会话的回退冲突比对——偏好目的地 vs 会话最新行程目的地。
     * 降级语义：任一失败返回 null（可见性信号不阻断主链路）。source=itinerary。
     */
    private com.travel.stream.service.ChatStreamExecutor.ChatStreamResult.PreferenceConflict
    conflictAgainstSessionItinerary(Long userId, String sessionId, String preferred) {
        try {
            java.util.List<Long> ids = itineraryBriefPort.findSessionItineraryIds(sessionId);
            if (ids.isEmpty()) {
                return null;
            }
            return itineraryBriefPort.briefOf(userId, ids.get(0))
                    .map(b -> b.destination())
                    .filter(d -> d != null && !d.isBlank() && !d.equals(preferred))
                    .map(d -> new com.travel.stream.service.ChatStreamExecutor
                            .ChatStreamResult.PreferenceConflict(preferred, d, "itinerary"))
                    .orElse(null);
        } catch (Exception e) {
            log.debug("[ChatService] 会话行程目的地回退比对降级: {}", e.getMessage());
            return null;
        }
    }

    /** M23b（E4）：从【锚定行程】段提取首个锚定目的地（单锚定首发；无则 null）。 */
    private String anchorBriefsDestination(String anchorSection) {
        if (anchorSection == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("目的地:([^\s]+)").matcher(anchorSection);
        return m.find() ? m.group(1) : null;
    }

    /**
     * M28-5：会话"首个行程"判定（自动锚定资格）。
     *
     * <p>M28-4 首版要求 findSessionItineraryIds 为空——但结果装配在路由回写之后执行，
     * 本轮新建的行程已挂到会话上（列表恒非空），条件永假（M23 询问卡同病从未真正触发）。
     * 修正语义：会话无任何行程，或唯一行程恰为本轮新建/改写的那个。</p>
     */
    static boolean isSessionFirstItinerary(Long routedItineraryId, java.util.List<Long> sessionItineraries) {
        if (routedItineraryId == null) {
            return false;
        }
        return sessionItineraries == null || sessionItineraries.isEmpty()
                || (sessionItineraries.size() == 1 && sessionItineraries.contains(routedItineraryId));
    }

    /**
     * M28-2：偏好-锚定冲突的回答口径说明指令（确定性文本，追加在 composed 末尾）。
     *
     * <p>要求模型在回答开头用一两句话向用户说明：锚定行程目的地与偏好目的地不一致、
     * 本轮按偏好目的地处理（受限原因与依据可见），并指引用户可通过输入框下方的
     * 冲突卡片选择「保留锚定」回到锚定行程。静态纯函数便于单测。</p>
     */
    static String conflictAnswerGuidance(String preferred, String anchored) {
        return "【本轮口径说明（回答要求）】检测到用户当前锚定行程的目的地为「" + anchored
                + "」，与用户偏好目的地「" + preferred + "」不一致。本轮行程已按偏好目的地「"
                + preferred + "」处理。请在回答的最开头先用一小段自然的话向用户说明这一点"
                + "（指出锚定行程与偏好目的地不一致、本轮按偏好目的地规划，属于系统按用户最新偏好执行的口径），"
                + "并提示用户：若想继续按锚定行程（" + anchored + "）调整，可点击输入框下方提示条中的「保留锚定」。"
                + "说明须简短（两三句内），之后再进入正题。";
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
        requireOwnedSession(userId, sessionId);
        chatTurnTitlePolicy.updateTitle(sessionStorePort, sessionId, title);
    }

    /** M5-1：基于首条用户消息生成会话标题（不引 LLM：短全量、长截断） */
    static String buildSessionTitle(String message, int maxLength) {
        return ChatTurnTitlePolicy.buildSessionTitle(message, maxLength);
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
     * <p>Z-4b：方法体迁出至 {@link ChatSessionQueryService}（结果 record 留守=公共 FQN 零变）。</p>
     */
    public CloseSessionResult closeSession(Long userId, String sessionId) {
        return chatSessionQueryService.closeSession(userId, sessionId);
    }

    /**
     * M6-56/T4：会话存在 + 归属校验收敛（40101 / 40404 / 40302）。
     */
    private ChatSession requireOwnedSession(Long userId, String sessionId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED.code(), ErrorCode.UNAUTHORIZED.message());
        }
        ChatSession session = chatPersistenceStep.requireSession(sessionId);
        if (!userId.equals(session.getUserId())) {
            throw new BusinessException(ErrorCode.SESSION_ACCESS_DENIED.code(),
                    ErrorCode.SESSION_ACCESS_DENIED.message());
        }
        return session;
    }
}
