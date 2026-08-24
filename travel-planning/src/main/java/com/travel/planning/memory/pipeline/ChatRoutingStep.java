package com.travel.planning.memory.pipeline;

import com.travel.planning.agent.supervisor.TravelSupervisorAgent;
import com.travel.planning.memory.chat.ChatIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * M3-17：MessagePipeline 步骤 8「路由」。
 * 意图分派（recall/direct/supervisor）从 ChatService 抽出为独立可测步骤。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatRoutingStep {

    /**
     * 路由结果：应答文本与本次全部 LLM 调用的真实 token 总量（F27 口径）。
     *
     * @param fallback M4-3：true=异常兜底文案（幂等登记 FAILED，重试不重放兜底）
     */
    public record RouteResult(String response, long aiTokens, boolean fallback) {
    }

    /**
     * M6：流式路由结果。
     *
     * @param streamed true=直答/回顾真 token 流已通过 tokenSink 输出（调用方不再
     *                 onResponse 重复发送）；false=规划/兜底，最终回答由
     *                 onResponse 统一分块输出
     */
    public record StreamRouteResult(String response, long aiTokens, boolean fallback,
                                    boolean streamed) {
    }

    private final TravelSupervisorAgent supervisorAgent;

    /**
     * 按意图分派：RECALL → 轻量回顾；PROFILE/CHAT/FUNCTIONAL → 入口直答；
     * PLANNING/REFINE → Supervisor 完整规划（F85/F64/F27 语义不变）。
     */
    public RouteResult route(ChatIntent intent, String composed, Long userId,
                             List<Map<String, Object>> sessionHits) {
        long routeStart = System.currentTimeMillis();
        String response;
        long aiTokens = 0;
        boolean fallback = false;
        try {
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
                            supervisorAgent.executePlanningWithUsage(composed, userId);
                    response = result.answer();
                    // F27：assistant 消息 tokens = 本次全部 LLM 调用的真实 totalTokens 之和
                    aiTokens = result.totalTokens();
                }
            }
        } catch (Exception e) {
            log.error("Agent 调用失败", e);
            response = "抱歉，处理您的请求时出现错误，请稍后重试。";
            fallback = true;
        }
        long routeElapsed = System.currentTimeMillis() - routeStart;
        log.info("[ChatRouting] intent={}, router={}, elapsedMs={}, fallback={}",
                intent, routerOf(intent), routeElapsed, fallback);
        return new RouteResult(response, aiTokens, fallback);
    }

    /**
     * M6：流式路由分派。
     *
     * <p>RECALL/PROFILE/CHAT/FUNCTIONAL → 真 token 流（tokenSink 逐增量回调）；
     * PLANNING/REFINE → 保持阻塞式 Supervisor，返回 streamed=false 由传输层分块；
     * 异常统一走兜底文案（fallback=true，streamed=false，幂等登记 FAILED）。</p>
     */
    public StreamRouteResult routeStream(ChatIntent intent, String composed, Long userId,
                                         List<Map<String, Object>> sessionHits,
                                         Consumer<String> tokenSink) {
        Consumer<String> sink = tokenSink == null ? t -> { } : tokenSink;
        long routeStart = System.currentTimeMillis();
        try {
            switch (intent) {
                case RECALL -> {
                    TravelSupervisorAgent.PlanningResult result =
                            supervisorAgent.answerRecallStream(composed, sessionHits, sink);
                    logElapsed(intent, routeStart, false);
                    return new StreamRouteResult(result.answer(), result.totalTokens(), false, true);
                }
                case PROFILE, CHAT, FUNCTIONAL -> {
                    TravelSupervisorAgent.PlanningResult result =
                            supervisorAgent.answerDirectStream(composed, userId, sink);
                    logElapsed(intent, routeStart, false);
                    return new StreamRouteResult(result.answer(), result.totalTokens(), false, true);
                }
                default -> {
                    // 规划/精调：阻塞式 Supervisor（B1 分块流，B2 图级流式专项）
                    RouteResult blocking = route(intent, composed, userId, sessionHits);
                    logElapsed(intent, routeStart, blocking.fallback());
                    return new StreamRouteResult(blocking.response(), blocking.aiTokens(),
                            blocking.fallback(), false);
                }
            }
        } catch (Exception e) {
            log.error("Agent 流式调用失败", e);
            return new StreamRouteResult("抱歉，处理您的请求时出现错误，请稍后重试。", 0, true, false);
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
}
