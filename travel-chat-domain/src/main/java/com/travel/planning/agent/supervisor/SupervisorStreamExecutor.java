package com.travel.planning.agent.supervisor;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.travel.core.guard.CircuitBreaker;
import com.travel.aigateway.route.ModelRoutingContext;
import com.travel.planning.memory.longterm.ProfileToolProvider;
import com.travel.planning.prompt.PromptTemplates;
import com.travel.planning.service.TurnCancellation;
import com.travel.planning.service.TurnInterruptedException;
import com.travel.planning.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * M6-58/T9 Step4：Supervisor 图级流式执行器（streamPlanningWithUsage 从
 * TravelSupervisorAgent 迁出）。
 *
 * <p>行为与迁移前逐字节等价：M6-18 节点 thinking + tokenSink 输出、M6-21 NodeOutput
 * token 兜底口径、M6-22 拦截器 peek 补写追溯、F77/B4-2 图流重试、F66 直答兜底、
 * M6-40/46 取消链与线程中断上抛均原样保留。直答兜底复用
 * {@link DirectAnswerExecutor#callDirect}（withBreaker=false，与迁移前一致）。</p>
 */
@Slf4j
final class SupervisorStreamExecutor {

    private final TokenUsageInterceptor tokenUsageInterceptor;
    private final CircuitBreaker.Registry circuitBreakerRegistry;
    private final PromptTemplates promptTemplates;
    private final DirectAnswerExecutor directAnswerExecutor;

    SupervisorStreamExecutor(TokenUsageInterceptor tokenUsageInterceptor,
                             CircuitBreaker.Registry circuitBreakerRegistry,
                             PromptTemplates promptTemplates,
                             DirectAnswerExecutor directAnswerExecutor) {
        this.tokenUsageInterceptor = tokenUsageInterceptor;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.promptTemplates = promptTemplates;
        this.directAnswerExecutor = directAnswerExecutor;
    }

    /**
     * M6-18：规划路径图级流式（默认由路由层关闭，开启前需 golden 验证）。
     *
     * <p>用 {@code supervisor.stream} 替代 {@code invoke}：每个 {@link NodeOutput}
     * 携带当时 {@link OverAllState}，取最后一个节点状态作为最终状态；
     * 节点名经 nodeThinking 输出（thinking 事件），最终回答经 tokenSink 分块输出。
     * 任何异常/空状态由调用方（ChatRoutingStep）降级回阻塞路径。</p>
     */
    TravelSupervisorAgent.StreamPlanningResult streamPlanningWithUsage(
            SupervisorAgent supervisor, String userInput, Long userId,
            BiConsumer<String, String> nodeThinking,
            Consumer<String> tokenSink,
            TurnCancellation cancellation) throws Exception {
        log.info("开始执行行程规划(图流): input={}, userId={}", userInput, userId);
        long start = System.currentTimeMillis();
        TurnCancellation cancel = cancellation == null ? TurnCancellation.NOOP : cancellation;
        String requestId = TraceContext.active() ? TraceContext.current().requestId
                : UUID.randomUUID().toString();
        tokenUsageInterceptor.begin(requestId);
        try {
            RunnableConfig.Builder configBuilder = RunnableConfig.builder()
                    .threadId("rag_" + requestId)
                    .addMetadata(TokenUsageInterceptor.REQUEST_ID_KEY, requestId);
            // M7 Level 2：图流 Reactor 线程经 metadata 传播模型 key（拦截器同线程 runWith）
            String model = ModelRoutingContext.current();
            if (model != null && !model.isBlank()) {
                configBuilder.addMetadata(ModelRouteInterceptor.MODEL_KEY, model);
            }
            ReactiveBlockSupport.addCancellationMetadata(configBuilder, cancel);
            if (userId != null) {
                configBuilder.addMetadata(ProfileToolProvider.USER_ID_METADATA_KEY, userId);
            }
            RunnableConfig config = configBuilder.build();
            AtomicReference<OverAllState> lastState = new AtomicReference<>();
            long[] nodeTokens = {0};
            String[] lastNodeLabel = {null};
            Flux<NodeOutput> flux = circuitBreakerRegistry.of("supervisor").call(
                    "supervisor", () -> streamSupervisorSafely(supervisor, userInput, config));
            ReactiveBlockSupport.blockUntilDone(flux, out -> {
                if (out.state() != null) {
                    lastState.set(out.state());
                }
                if (out.tokenUsage() != null && out.tokenUsage().getTotalTokens() != null) {
                    // M6-21：NodeOutput.tokenUsage 为累计口径，取最大值近似本次总用量
                    nodeTokens[0] = Math.max(nodeTokens[0], out.tokenUsage().getTotalTokens());
                }
                String label = friendlyNode(out.node());
                if (label != null && !label.equals(lastNodeLabel[0]) && nodeThinking != null) {
                    lastNodeLabel[0] = label;
                    nodeThinking.accept("routing", label);
                }
            }, cancel, TravelSupervisorAgent.MAX_EXECUTION_SECONDS);

            OverAllState finalState = lastState.get();
            if (finalState == null) {
                throw new IllegalStateException("图流未返回最终状态");
            }
            String result = SupervisorResponseSupport.buildFinalResponse(finalState);
            // M6-22：先 peek 再 endAndGet，补齐图流路径的追溯 token 写入（与阻塞路径对齐）；
            // NodeOutput.tokenUsage 仅作拦截器失效时的兜底（M6-21 证明其口径不可信）。
            long[] streamUsage = tokenUsageInterceptor.peek(requestId);
            long interceptorTokens = tokenUsageInterceptor.endAndGet(requestId);
            long totalTokens = interceptorTokens > 0 ? interceptorTokens : nodeTokens[0];
            if (interceptorTokens <= 0) {
                log.warn("图流拦截器未采集到 token（可能框架路径变化），使用 NodeOutput 兜底={}",
                        nodeTokens[0]);
            }
            SupervisorTraceSupport.applyTraceTokens(streamUsage);
            SupervisorTraceSupport.applyTracePath(finalState);

            // F77/B4-2：四键全空且疑似规划 → 整图重试一次；仍空走直答兜底（镜像阻塞路径语义）
            if (!SupervisorResponseSupport.hasSectionOutput(finalState)
                    && PlanningHeuristics.looksLikePlanningRequest(userInput)
                    && !PlanningHeuristics.isRecallQuery(userInput)) {
                // M6-40：整图重试前检查取消
                cancel.throwIfCancelled();
                String retryRequestId = UUID.randomUUID().toString();
                tokenUsageInterceptor.begin(retryRequestId);
                try {
                    RunnableConfig.Builder retryBuilder = RunnableConfig.builder()
                            .threadId("rag_" + retryRequestId)
                            .addMetadata(TokenUsageInterceptor.REQUEST_ID_KEY, retryRequestId);
                    if (model != null && !model.isBlank()) {
                        retryBuilder.addMetadata(ModelRouteInterceptor.MODEL_KEY, model);
                    }
                    ReactiveBlockSupport.addCancellationMetadata(retryBuilder, cancel);
                    if (userId != null) {
                        retryBuilder.addMetadata(ProfileToolProvider.USER_ID_METADATA_KEY, userId);
                    }
                    AtomicReference<OverAllState> retryState = new AtomicReference<>();
                    long[] retryNodeTokens = {0};
                    Flux<NodeOutput> retryFlux = circuitBreakerRegistry.of("supervisor").call(
                            "supervisor", () -> streamSupervisorSafely(
                                    supervisor, userInput, retryBuilder.build()));
                    ReactiveBlockSupport.blockUntilDone(retryFlux, out -> {
                        if (out.state() != null) {
                            retryState.set(out.state());
                        }
                        if (out.tokenUsage() != null && out.tokenUsage().getTotalTokens() != null) {
                            retryNodeTokens[0] = Math.max(retryNodeTokens[0],
                                    out.tokenUsage().getTotalTokens());
                        }
                    }, cancel, TravelSupervisorAgent.MAX_EXECUTION_SECONDS);
                    OverAllState retried = retryState.get();
                    if (retried != null && SupervisorResponseSupport.hasSectionOutput(retried)) {
                        finalState = retried;
                        result = SupervisorResponseSupport.buildFinalResponse(retried);
                        long[] retryUsage = tokenUsageInterceptor.peek(retryRequestId);
                        long retryInterceptor = tokenUsageInterceptor.endAndGet(retryRequestId);
                        if (retryInterceptor <= 0) {
                            log.warn("图流重试拦截器未采集到 token，使用 NodeOutput 兜底={}",
                                    retryNodeTokens[0]);
                        }
                        totalTokens += retryInterceptor > 0
                                ? retryInterceptor : retryNodeTokens[0];
                        SupervisorTraceSupport.applyTraceTokens(retryUsage);
                        SupervisorTraceSupport.applyTracePath(retried);
                    } else {
                        tokenUsageInterceptor.endAndGet(retryRequestId);
                    }
                } catch (TurnInterruptedException e) {
                    tokenUsageInterceptor.endAndGet(retryRequestId);
                    throw e;
                } catch (Exception e) {
                    tokenUsageInterceptor.endAndGet(retryRequestId);
                    log.warn("图流重试失败，保留原结果: {}", e.getMessage());
                }
            }
            if (!SupervisorResponseSupport.hasSectionOutput(finalState)) {
                // M6-42：图流直答兜底前检查取消（不再发起新的 LLM 调用）
                cancel.throwIfCancelled();
                ChatResponse direct = directAnswerExecutor.callDirect(
                        PlanningHeuristics.isRecallQuery(userInput)
                                ? promptTemplates.directRecallSystem()
                                : promptTemplates.directAnswerSystem(),
                        userInput, false);
                String text = direct.getResult() != null && direct.getResult().getOutput() != null
                        ? direct.getResult().getOutput().getText() : null;
                if (text != null && !text.isBlank()) {
                    result = text.trim();
                    if (direct.getMetadata() != null && direct.getMetadata().getUsage() != null) {
                        Usage u = direct.getMetadata().getUsage();
                        totalTokens += u.getTotalTokens() != null ? u.getTotalTokens() : 0;
                        DirectAnswerExecutor.applyDirectTokens(direct);
                    }
                }
            }
            if (tokenSink != null && result != null) {
                tokenSink.accept(result);
            }
            long cost = System.currentTimeMillis() - start;
            log.info("行程规划完成(图流), 耗时={}ms, 结果长度={}, tokens={}", cost,
                    result == null ? 0 : result.length(), totalTokens);
            return new TravelSupervisorAgent.StreamPlanningResult(result, totalTokens, false);
        } catch (Exception e) {
            tokenUsageInterceptor.endAndGet(requestId);
            throw e;
        }
    }

    /** M6-21：把图节点名映射为友好 thinking；内部节点返回 null 跳过 */
    private static String friendlyNode(String node) {
        if (node == null || node.isBlank()) {
            return null;
        }
        if (node.contains("preference_analysis")) {
            return "正在分析偏好…";
        }
        if (node.contains("attraction_filter")) {
            return "正在筛选景点…";
        }
        if (node.contains("route_arrangement")) {
            return "正在编排每日行程…";
        }
        if (node.contains("budget_estimation")) {
            return "正在估算预算…";
        }
        if (node.contains("supervisor")) {
            return "正在路由规划子任务…";
        }
        return null;
    }

    /** M6-18：图流安全包装（GraphRunnerException 受检异常 → RuntimeException） */
    private static Flux<NodeOutput> streamSupervisorSafely(
            SupervisorAgent supervisor, String userInput, RunnableConfig config) {
        try {
            return supervisor.stream(userInput, config);
        } catch (Exception e) {
            throw new RuntimeException("Supervisor 图流失败", e);
        }
    }
}
