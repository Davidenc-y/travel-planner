package com.travel.planning.memory.pipeline;

import com.travel.planning.service.ResponseTexts;
import com.travel.planning.agent.supervisor.TravelSupervisorAgent;
import com.travel.planning.agent.supervisor.SupervisorResponseSupport;
import com.travel.planning.agent.support.AttractionGroundingChecker;
import com.travel.planning.agent.support.ChatWeatherContextPort;
import com.travel.planning.agent.support.ItineraryConflictPort;
import com.travel.planning.agent.support.ItineraryVersionPort;
import com.travel.planning.memory.chat.ChatIntent;
import com.travel.planning.memory.knowledge.SessionContextChunker;
import com.travel.planning.memory.knowledge.SessionKnowledgeWriter;
import com.travel.planning.service.ModelQuotaExceptionSupport;
import com.travel.planning.service.ModelCircuitExceptionSupport;
import com.travel.planning.service.TurnCancellation;
import com.travel.planning.service.TurnInterruptedException;
import com.travel.planning.stream.ChatStreamProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * M3-17：MessagePipeline 步骤 8「路由」。
 * 意图分派（recall/direct/supervisor）从 ChatService 抽出为独立可测步骤。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatRoutingStep implements ChatPipelineStep {

    /** B3.2：步骤顺序——M3-17 步骤 8「路由」（依据 R7-pipeline-mapping 现发送链步骤序 8，终步序号最大，ChatService :583/:588）。 */
    static final int STEP_ORDER = 8;

    @Override
    public int order() {
        return STEP_ORDER;
    }

    /**
     * 路由结果：应答文本与本次全部 LLM 调用的真实 token 总量（F27 口径）。
     *
     * @param fallback M4-3：true=异常兜底文案（幂等登记 FAILED，重试不重放兜底）
     */
    public record RouteResult(String response, long aiTokens, boolean fallback,
                              Long writtenItineraryId) {
        /** M23 前的三参兼容构造。 */
        public RouteResult(String response, long aiTokens, boolean fallback) {
            this(response, aiTokens, fallback, null);
        }
    }

    /**
     * M6：流式路由结果。
     *
     * @param streamed true=直答/回顾真 token 流已通过 tokenSink 输出（调用方不再
     *                 onResponse 重复发送）；false=规划/兜底，最终回答由
     *                 onResponse 统一分块输出
     */
    public record StreamRouteResult(String response, long aiTokens, boolean fallback,
                                    boolean streamed, Long writtenItineraryId) {
        /** M23 前的四参兼容构造。 */
        public StreamRouteResult(String response, long aiTokens, boolean fallback, boolean streamed) {
            this(response, aiTokens, fallback, streamed, null);
        }
    }

    private final TravelSupervisorAgent supervisorAgent;
    private final ChatStreamProperties chatStreamProps;
    /** M8-2：生成端引用校验（观测模式，只写 trace 不阻断输出） */
    private final AttractionGroundingChecker groundingChecker;
    /** M8-9：会话知识写入（itinerary_day 切片，解锁 RECALL/REFINE retention） */
    private final SessionKnowledgeWriter sessionKnowledgeWriter;
    private final SessionContextChunker sessionContextChunker;

    /** M9-4：冲突校验 Port（planning 实现注入；null=关闭/未装配） */
    private ItineraryConflictPort itineraryConflictPort;

    /** M13-2：行程版本回写 Port（planning 本地实现或 WebFlux HTTP 桥注入；null=关闭/未装配） */
    private ItineraryVersionPort itineraryVersionPort;

    /** M15-1：聊天规划天气上下文 Port（planning 实现注入；null=未装配） */
    private ChatWeatherContextPort chatWeatherContextPort;

    /** M9-4：冲突观测开关（false=完全关闭，行为等价现状） */
    @Value("${travel.chat.supervisor.conflict-observe.enabled:true}")
    private boolean conflictObserveEnabled = true;

    /** M13-2：行程落库/回写总开关（false=行为等价现状） */
    @Value("${travel.chat.supervisor.itinerary-writeback.enabled:true}")
    private boolean itineraryWritebackEnabled = true;

    @Autowired(required = false)
    void setItineraryConflictPort(ItineraryConflictPort itineraryConflictPort) {
        this.itineraryConflictPort = itineraryConflictPort;
    }

    /** MVC planning 应用装配本地实现（travel-planning 扫描到它） */
    @Autowired(required = false)
    @Qualifier("itineraryVersionPortImpl")
    void setLocalItineraryVersionPort(ItineraryVersionPort itineraryVersionPort) {
        this.itineraryVersionPort = itineraryVersionPort;
    }

    /** WebFlux(8083) 应用装配 HTTP 桥（travel-stream-webflux 不依赖 travel-planning） */
    @Autowired(required = false)
    @Qualifier("webfluxItineraryVersionPort")
    void setBridgeItineraryVersionPort(ItineraryVersionPort itineraryVersionPort) {
        this.itineraryVersionPort = itineraryVersionPort;
    }

    @Autowired(required = false)
    void setChatWeatherContextPort(ChatWeatherContextPort chatWeatherContextPort) {
        this.chatWeatherContextPort = chatWeatherContextPort;
    }

    /**
     * 按意图分派：RECALL → 轻量回顾；PROFILE/CHAT/FUNCTIONAL → 入口直答；
     * PLANNING/REFINE → Supervisor 完整规划（F85/F64/F27 语义不变）。
     *
     * @param cancellation M6-42：轮次取消令牌（null 兼容 NOOP）；阻塞路径入口检查，
     *                     规划执行中由拦截器短路 + get 前/后检查兜底
     */
    public RouteResult route(ChatIntent intent, String composed, Long userId,
                             String sessionId,
                             List<Map<String, Object>> sessionHits,
                             TurnCancellation cancellation) {
        Long writtenItineraryId = null; // M23（P-D）：回写成功的行程 id（供 suggestion 判定）
        TurnCancellation cancel = cancellation == null ? TurnCancellation.NOOP : cancellation;
        long routeStart = System.currentTimeMillis();
        String response;
        long aiTokens = 0;
        boolean fallback = false;
        try {
            // M6-42：路由入口检查（取消后不发起任何 LLM 调用）
            cancel.throwIfCancelled();
            switch (intent) {
                case RECALL -> {
                    // F85：轻量回顾管线（itinerary_day 骨架 + LLM 润色，零编造）
                    TravelSupervisorAgent.PlanningResult result =
                            supervisorAgent.answerRecall(composed, sessionHits);
                    response = result.answer();
                    aiTokens = result.totalTokens();
                }
                case PROFILE, CHAT, FUNCTIONAL -> {
                    // F85：入口直答（不触发 supervisor，覆盖优先级 system 指令）
                    TravelSupervisorAgent.PlanningResult result =
                            supervisorAgent.answerDirect(composed, userId);
                    response = result.answer();
                    aiTokens = result.totalTokens();
                }
                default -> { // PLANNING / REFINE：F64/B2 把 userId 传入 Supervisor（metadata 供画像工具）
                    TravelSupervisorAgent.PlanningResult result =
                            supervisorAgent.executePlanningWithUsage(
                                    withWeather(intent, composed), userId, cancel);
                    response = result.answer();
                    // F27：assistant 消息 tokens = 本次全部 LLM 调用的真实 totalTokens 之和
                    aiTokens = result.totalTokens();
                    // M8-2：组装回答后做确定性引用校验（候选名从 composed 提取）
                    SupervisorResponseSupport.recordGrounding(groundingChecker, composed, response);
                    // M8-6：REFINE 保留性观测（原行程 vs 新输出静默丢失率写 trace）
                    if (intent == ChatIntent.REFINE) {
                        SupervisorResponseSupport.recordRetention(
                                groundingChecker, sessionHits, response);
                    }
                    // M8-9：行程生成后写入 itinerary_day 切片（REFINE 覆盖旧版本）
                    observeConflictIfEnabled(composed, result.routePlanJson());
                    writeItineraryChunks(sessionId, result.routePlanJson());
                    writtenItineraryId = writebackIfEnabled(intent, userId, sessionId, composed,
                            result.routePlanJson(), result.budgetJson());
                }
            }
        } catch (TurnInterruptedException e) {
            // M6-42：中断终止必须向上传递（failTurn + 不落库），不得转为兜底文案
            throw e;
        } catch (Exception e) {
            // M8-9i：模型额度不足必须上抛 40303（前端展示明确提示），
            // 不得吞成“抱歉，请稍后重试”兜底文案
            ModelCircuitExceptionSupport.rethrowIfCircuitOpen(e);
            ModelQuotaExceptionSupport.rethrowIfQuotaExceeded(e);
            log.error("Agent 调用失败", e);
            response = ResponseTexts.GENERIC_ROUTE_FAILURE;
            fallback = true;
        }
        long routeElapsed = System.currentTimeMillis() - routeStart;
        log.info("[ChatRouting] intent={}, router={}, elapsedMs={}, fallback={}",
                intent, routerOf(intent), routeElapsed, fallback);
        return new RouteResult(response, aiTokens, fallback, writtenItineraryId);
    }

    /**
     * M6：流式路由分派。
     *
     * <p>RECALL/PROFILE/CHAT/FUNCTIONAL → 真 token 流（tokenSink 逐增量回调）；
     * PLANNING/REFINE → 保持阻塞式 Supervisor，返回 streamed=false 由传输层分块；
     * 异常统一走兜底文案（fallback=true，streamed=false，幂等登记 FAILED）。</p>
     */
    public StreamRouteResult routeStream(ChatIntent intent, String composed, Long userId,
                                         String sessionId,
                                         List<Map<String, Object>> sessionHits,
                                         TurnCancellation cancellation,
                                         Consumer<String> tokenSink,
                                         BiConsumer<String, String> thinkingSink) {
        Consumer<String> sink = tokenSink == null ? t -> { } : tokenSink;
        BiConsumer<String, String> think = thinkingSink == null ? (s, m) -> { } : thinkingSink;
        long routeStart = System.currentTimeMillis();
        try {
            switch (intent) {
                case RECALL -> {
                    TravelSupervisorAgent.PlanningResult result =
                            supervisorAgent.answerRecallStream(composed, sessionHits, sink, cancellation);
                    logElapsed(intent, routeStart, false);
                    return new StreamRouteResult(result.answer(), result.totalTokens(), false, true);
                }
                case PROFILE, CHAT, FUNCTIONAL -> {
                    TravelSupervisorAgent.PlanningResult result =
                            supervisorAgent.answerDirectStream(composed, userId, sink, cancellation);
                    logElapsed(intent, routeStart, false);
                    return new StreamRouteResult(result.answer(), result.totalTokens(), false, true);
                }
                default -> {
                    // M6-18：图级流式开关（默认关；开启前需 golden 验证），失败自动降级阻塞
                    if (chatStreamProps.isPlanningGraphStreamEnabled()) {
                        // M6-38：用户停止（SSE abort）会让图流线程收到 InterruptedException——
                        // 此时必须终止，不得降级阻塞再启动一轮完整 LLM 规划
                        try {
                            TravelSupervisorAgent.StreamPlanningResult r =
                                    supervisorAgent.streamPlanningWithUsage(
                                            withWeather(intent, composed),
                                            userId, think, sink, cancellation);
                            SupervisorResponseSupport.recordGrounding(
                                    groundingChecker, composed, r.answer());
                            observeConflictIfEnabled(composed, r.routePlanJson());
                            writeItineraryChunks(sessionId, r.routePlanJson());
                            Long writtenId = writebackIfEnabled(intent, userId, sessionId, composed,
                                    r.routePlanJson(), r.budgetJson());
                            logElapsed(intent, routeStart, r.fallback());
                            return new StreamRouteResult(r.answer(), r.totalTokens(),
                                    r.fallback(), true, writtenId);
                        }
                        catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new TurnInterruptedException("轮次已中断");
                        } catch (TurnInterruptedException e) {
                            // M6-46：中断终止必须上抛——不得被内层 catch(Exception)
                            // 吞掉后误走"降级阻塞"（M6-38 语义；blockUntilDone 现直接
                            // 抛 TurnInterruptedException，此分支覆盖运行态）
                            throw e;
                        } catch (Exception e) {
                            // M8-9i：额度不足不得降级阻塞重跑（会再次 403 且多耗请求），
                            // 直接上抛由外层统一转换
                            ModelCircuitExceptionSupport.rethrowIfCircuitOpen(e);
                            ModelQuotaExceptionSupport.rethrowIfQuotaExceeded(e);
                            log.warn("[ChatRouting][graph-stream] 图流失败，降级阻塞: {}",
                                    e.getMessage());
                        }
                    }
                    // 规划/精调：阻塞式 Supervisor（B1 分块流）
                    RouteResult blocking = route(intent, composed, userId, sessionId,
                            sessionHits, cancellation);
                    logElapsed(intent, routeStart, blocking.fallback());
                    return new StreamRouteResult(blocking.response(), blocking.aiTokens(),
                            blocking.fallback(), false);
                }
            }
        } catch (java.util.concurrent.CancellationException e) {
            Thread.currentThread().interrupt();
            throw new TurnInterruptedException("轮次已中断");
        } catch (TurnInterruptedException e) {
            // 中断终止必须向上传递（failTurn + 不落库），不得转为兜底文案
            throw e;
        } catch (Exception e) {
            // M8-9i：模型额度不足必须上抛 40303，不得吞成兜底文案
            ModelCircuitExceptionSupport.rethrowIfCircuitOpen(e);
            ModelQuotaExceptionSupport.rethrowIfQuotaExceeded(e);
            log.error("Agent 流式调用失败", e);
            return new StreamRouteResult(ResponseTexts.GENERIC_ROUTE_FAILURE, 0, true, false);
        }
    }

    private static void logElapsed(ChatIntent intent, long start, boolean fallback) {
        long elapsed = System.currentTimeMillis() - start;
        log.info("[ChatRouting][stream] intent={}, router={}, elapsedMs={}, fallback={}",
                intent, routerOf(intent), elapsed, fallback);
    }

    private static String routerOf(ChatIntent intent) {
        return switch (intent) {
            case RECALL -> "recall";
            case PROFILE, CHAT, FUNCTIONAL -> "direct";
            default -> "supervisor";
        };
    }

    /** M15-1：PLANNING/REFINE 前拼接天气参考段；不可用时不改变输入。 */
    private String withWeather(ChatIntent intent, String composed) {
        if (intent != ChatIntent.PLANNING && intent != ChatIntent.REFINE
                || composed == null) {
            return composed;
        }
        // M26-F4：注入当前日期上下文（确定性；用户未指定出发日期时禁止编造过去日期）
        // M28-11：标记常量化（ItineraryVersionPortImpl 以此为右边界截取用户原始输入）
        java.time.LocalDate today = java.time.LocalDate.now();
        String dow = today.getDayOfWeek().getDisplayName(
                java.time.format.TextStyle.FULL, java.util.Locale.CHINESE);
        String dateLine = "\n" + com.travel.planning.prompt.Markers.CURRENT_DATE + today + "（" + dow + "）。用户未指定出发日期时，"
                + "行程日期从今天之后合理选择或用『第N天』相对表述，禁止编造已过去的日期。\n";
        composed = composed + dateLine;
        if (chatWeatherContextPort == null) {
            return composed;
        }
        String weather = chatWeatherContextPort.build(composed);
        return weather == null || weather.isBlank() ? composed : weather + "\n\n" + composed;
    }

    /**
     * M8-9：把 Supervisor 规划结果按天切片写入当前会话知识。
     *
     * <p>先按 seq 前缀 {@code itin:<sessionId>:} 删除旧版本（REFINE/重生成覆盖），
     * 再写入新切片；任一步失败仅 WARN（残留旧切片只影响观测，不阻断主流程）。</p>
     */
    private void writeItineraryChunks(String sessionId, String routePlanJson) {
        if (sessionId == null || sessionId.isBlank()
                || routePlanJson == null || routePlanJson.isBlank()) {
            return;
        }
        try {
            String trimmed = routePlanJson.trim();
            // state 的 routePlan 是 {"days":[...]}；chunkItinerary 期望 {"routePlan": {...}}
            String itineraryJson = trimmed.startsWith("{") && trimmed.contains("\"days\"")
                    ? "{\"routePlan\":" + trimmed + "}" : trimmed;
            String prefix = "itin:" + sessionId + ":";
            sessionKnowledgeWriter.deleteBySeqPrefix(sessionId, prefix);
            sessionKnowledgeWriter.writeAsync(sessionId,
                    sessionContextChunker.chunkItinerary(sessionId, itineraryJson, null));
            log.info("[ChatRouting] itinerary_day 切片已写入会话知识: sessionId={}", sessionId);
        } catch (Exception e) {
            log.warn("[ChatRouting] itinerary_day 切片写入失败（不影响主流程）: sessionId={}, error={}",
                    sessionId, e.getMessage());
        }
    }

    /**
     * M13-2：聊天规划/REFINE 结果同步为行程资产（create-on-chat / REFINE 回写建版）。
     * 仅观测/资产层变化，不参与回答组装；失败静默降级。
     */
    private Long writebackIfEnabled(ChatIntent intent, Long userId, String sessionId,
                                    String userInput, String routePlanJson, String budgetJson) {
        final Long[] writtenIdHolder = {null}; // M23（P-D）：lambda 内赋值用数组持有
        if (!itineraryWritebackEnabled || itineraryVersionPort == null
                || intent != ChatIntent.PLANNING && intent != ChatIntent.REFINE) {
            return null;
        }
        if (routePlanJson == null || routePlanJson.isBlank()) {
            // M20-1：不再静默——2026-09-06 实证 REFINE 因图流输出丢失而无声跳过建版，
            // 用户仅在详情页发现"少了一个版本"。WARN 暴露原因供诊断。
            log.warn("[ItineraryWriteback] 跳过回写（routePlan 为空，图流子Agent 输出未合并或走了直答兜底）: "
                    + "sessionId={}, intent={}, answer将不建版", sessionId, intent);
            return null;
        }
        try {
            java.util.Optional<Long> itineraryId = itineraryVersionPort.syncAfterPlanning(
                    userId, sessionId, userInput, routePlanJson, budgetJson);
            itineraryId.ifPresent(id -> {
                writtenIdHolder[0] = id;
                log.info("[ItineraryWriteback] 行程资产已同步: itineraryId={}, sessionId={}, intent={}",
                        id, sessionId, intent);
            });
        } catch (Exception e) {
            log.warn("[ItineraryWriteback] 同步失败（不影响主流程）: sessionId={}, error={}",
                    sessionId, e.getMessage());
        }
        return writtenIdHolder[0];
    }

    /**
     * M9-4：规划成功后做观测级冲突校验（只写 trace，不阻断、不重试）。
     */
    private void observeConflictIfEnabled(String composed, String routePlanJson) {
        if (!conflictObserveEnabled || itineraryConflictPort == null) {
            return;
        }
        SupervisorResponseSupport.recordChatConflict(
                itineraryConflictPort, routePlanJson, composed);
    }
}
