package com.travel.planning.agent.supervisor;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.travel.core.guard.CircuitBreaker;
import com.travel.aigateway.route.ModelRoutingContext;
import com.travel.planning.memory.longterm.ProfileToolProvider;
import com.travel.memory.prompt.PromptTemplates;
import com.travel.stream.service.TurnCancellation;
import com.travel.stream.service.TurnInterruptedException;
import com.travel.planning.agent.supervisor.support.ActionFingerprinter;
import com.travel.planning.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    private final QuotaTripwire quotaTripwire;
    // M18-1：启发式判定（实例化注入）
    private final PlanningHeuristics planningHeuristics;

    SupervisorStreamExecutor(TokenUsageInterceptor tokenUsageInterceptor,
                             CircuitBreaker.Registry circuitBreakerRegistry,
                             PromptTemplates promptTemplates,
                             DirectAnswerExecutor directAnswerExecutor,
                             QuotaTripwire quotaTripwire,
                            PlanningHeuristics planningHeuristics) {
        this.tokenUsageInterceptor = tokenUsageInterceptor;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.promptTemplates = promptTemplates;
        this.directAnswerExecutor = directAnswerExecutor;
        this.quotaTripwire = quotaTripwire;
        this.planningHeuristics = planningHeuristics;
    }

    /** S-C1：动作指纹器（C-0；四维振荡裁决第一维数据源，缺省自给实例） */
    private ActionFingerprinter fingerprinter = new ActionFingerprinter();

    /** S-C2b：意图分级预算（缺省自给=未接线时等价内置档；墙钟 clamp 只紧不松） */
    private com.travel.planning.config.ChatBudgetPresets budgetPresets = new com.travel.planning.config.ChatBudgetPresets();

    @Autowired(required = false)
    void setBudgetPresets(com.travel.planning.config.ChatBudgetPresets budgetPresets) {
        this.budgetPresets = budgetPresets;
    }

    /** S-C2b：意图预算墙钟秒数——preset 与既有 MAX_EXECUTION_SECONDS 取小（只紧不松，E-33 式） */
    private long budgetWallSeconds() {
        String intent = TraceContext.active() ? TraceContext.current().budgetIntent : null;
        if (intent == null || intent.isBlank()) {
            return TravelSupervisorAgent.MAX_EXECUTION_SECONDS;
        }
        long presetSeconds = budgetPresets.resolve(intent).getWallMs() / 1000L;
        return Math.min(presetSeconds, TravelSupervisorAgent.MAX_EXECUTION_SECONDS);
    }

    @Autowired(required = false)
    void setFingerprinter(ActionFingerprinter fingerprinter) {
        this.fingerprinter = fingerprinter;
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
        // M8-9m：短路作用域优先轮次 key（图流重试复用），缺失回退 requestId
        String scopeKey = cancel.clientMessageId() != null && !cancel.clientMessageId().isBlank()
                ? cancel.clientMessageId() : requestId;
        tokenUsageInterceptor.begin(requestId);
        try {
            RunnableConfig.Builder configBuilder = RunnableConfig.builder()
                    .threadId("rag_" + requestId)
                    .addMetadata(TokenUsageInterceptor.REQUEST_ID_KEY, requestId);
            // M7 Level 2：图流 Reactor 线程经 metadata 传播模型 key（拦截器同线程 runWith）
            String model = ModelRoutingContext.current();
            if (model != null && !model.isBlank()) {
                configBuilder.addMetadata(ModelRouteInterceptor.MODEL_KEY, model);
                log.info("[ModelRoute] 图流模型已注入 metadata: requestId={}, model={}",
                        requestId, model);
            } else {
                // M8-9j：请求级模型缺失时图流将走注册表默认 main——
                // 明确告警，避免“选了模型却没生效”被静默吞掉
                log.warn("[ModelRoute] 图流请求级模型为空，将使用角色默认 main: requestId={}",
                        requestId);
            }
            ReactiveBlockSupport.addCancellationMetadata(configBuilder, cancel);
            // S-C2c：预算 token 门上限经 metadata 显式传递（拦截器跨线程读取，E-48 合规）
            if (TraceContext.active() && TraceContext.current().budgetIntent != null) {
                configBuilder.addMetadata(TokenUsageInterceptor.BUDGET_MAX_TOKENS_KEY,
                        budgetPresets.resolve(TraceContext.current().budgetIntent).getMaxTokens());
            }
            if (userId != null) {
                configBuilder.addMetadata(ProfileToolProvider.USER_ID_METADATA_KEY, userId);
            }
            RunnableConfig config = configBuilder.build();
            AtomicReference<OverAllState> lastState = new AtomicReference<>();
            long[] nodeTokens = {0};
            String[] lastNodeLabel = {null};
            Flux<NodeOutput> flux = circuitBreakerRegistry.of("supervisor").call(
                    "supervisor", () -> streamSupervisorSafely(supervisor, userInput, config));
            // M9-3c：同节点执行次数观测（图流成本治理第③层）
            Map<String, Integer> nodeExecutionCounts = new ConcurrentHashMap<>();
            long[] firstOutputAt = {0}; // S-B7：首 node 输出事件=图流首 token 等价打点
            // S-C1：四维振荡裁决状态（指纹窗口在 fingerprinter 内；其余三维按节点追踪）
            Map<String, Integer> nodeVisits = new ConcurrentHashMap<>();
            Map<String, Integer> nodeHashes = new ConcurrentHashMap<>();
            Map<String, Integer> nodeKeyCounts = new ConcurrentHashMap<>();
            Map<String, Integer> oscillationHits = new TreeMap<>();
            ReactiveBlockSupport.blockUntilDone(flux, out -> {
                if (firstOutputAt[0] == 0) {
                    firstOutputAt[0] = System.currentTimeMillis();
                }
                if (out.node() != null) {
                    nodeExecutionCounts.merge(out.node(), 1, Integer::sum);
                    OverAllState os = out.state();
                    if (os != null) {
                        String outputText = SupervisorResponseSupport.toText(os.value(out.node()));
                        int keyCount = os.data() == null ? 0 : os.data().size();
                        boolean hit = isOscillating(fingerprinter, requestId, out.node(),
                                nodeVisits, nodeHashes, nodeKeyCounts, outputText, keyCount);
                        if (hit) {
                            oscillationHits.merge(out.node(), 1, Integer::sum);
                        }
                    }
                }
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
            }, cancel, budgetWallSeconds());

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
            if (firstOutputAt[0] > 0) {
                SupervisorTraceSupport.applyTraceTtft(firstOutputAt[0] - start); // S-B7
            }
            SupervisorTraceSupport.applyTracePath(finalState);

            // F77/B4-2：四键全空且疑似规划 → 整图重试一次；仍空走直答兜底（镜像阻塞路径语义）
            if (!SupervisorResponseSupport.hasSectionOutput(finalState)
                    && planningHeuristics.looksLikePlanningRequest(userInput)
                    && !planningHeuristics.isRecallQuery(userInput)) {
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
                        if (out.node() != null) {
                            nodeExecutionCounts.merge(out.node(), 1, Integer::sum);
                        }
                        if (out.state() != null) {
                            retryState.set(out.state());
                        }
                        if (out.tokenUsage() != null && out.tokenUsage().getTotalTokens() != null) {
                            retryNodeTokens[0] = Math.max(retryNodeTokens[0],
                                    out.tokenUsage().getTotalTokens());
                        }
                    }, cancel, budgetWallSeconds());
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
                        planningHeuristics.isRecallQuery(userInput)
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
            // S-C1：四维合议告警（日志契约保留，附 reason 四元组；单纯 START×N 不再触发）
            if (!oscillationHits.isEmpty()) {
                List<String> warnings = oscillationHits.entrySet().stream()
                        .map(e -> e.getKey() + "=" + nodeExecutionCounts.get(e.getKey()))
                        .toList();
                log.warn("[GraphFlow] 节点疑似路由震荡: {} reason=fpRepeat>=2&hashSame&keysStale&cycle",
                        warnings);
                if (TraceContext.active()) {
                    TraceContext.current().graphFlowWarnings =
                            com.travel.common.util.JsonUtils.toJson(warnings);
                }
            }
            if (tokenSink != null && result != null) {
                tokenSink.accept(result);
            }
            long cost = System.currentTimeMillis() - start;
            log.info("行程规划完成(图流), 耗时={}ms, 结果长度={}, tokens={}", cost,
                    result == null ? 0 : result.length(), totalTokens);
            // M8-9：最终 state 的 routePlan JSON 随结果返回（供会话知识 itinerary_day 切片写入）
            String routePlanJson = SupervisorResponseSupport.toText(finalState.value("routePlan"));
            // M20-1：outputKey 为空时从 messages 抢救（并行派发合并失败场景），保证 REFINE 建版不丢
            if (routePlanJson == null || routePlanJson.isBlank()) {
                String salvaged = SupervisorResponseSupport.salvageRoutePlan(finalState);
                if (salvaged != null) {
                    log.warn("[Supervisor] routePlan outputKey 为空，已从 messages 抢救回退（长度={}）", salvaged.length());
                    routePlanJson = salvaged;
                }
            }
            String budgetJson = SupervisorResponseSupport.toText(
                    finalState.value("budgetEstimate"));
            return new TravelSupervisorAgent.StreamPlanningResult(
                    result, totalTokens, false, routePlanJson, budgetJson);
        } catch (Exception e) {
            tokenUsageInterceptor.endAndGet(requestId);
            throw e;
        } finally {
            // M8-9m：请求结束清理额度短路状态（与 token 采集 endAndGet 对称）
            quotaTripwire.clear(scopeKey);
        }
    }

    /**
     * S-C1：四维振荡合议（替换 M9-3c 朴素 >3 计数——START×N 正常多轮误报归零）。
     * 四维同时成立才判振荡：①指纹窗口重复≥2 ②输出哈希一致 ③state outputKeys 无增长
     * ④调用图动态重访（同节点第二次出现）。只读快照（F84：不读原始 state 对象引用）。
     * 包内可见便于回放单测。
     */
    static boolean isOscillating(ActionFingerprinter fingerprinter, String requestId, String node,
                                 Map<String, Integer> nodeVisits,
                                 Map<String, Integer> nodeHashes,
                                 Map<String, Integer> nodeStateKeyCounts,
                                 String outputText, int stateKeyCount) {
        String fp = fingerprinter.fingerprint(node, node, node, null);
        boolean dim1FpRepeat = fingerprinter.record(requestId, fp) >= 2;
        int hash = outputText == null ? 0 : outputText.hashCode();
        Integer lastHash = nodeHashes.get(node);
        boolean dim2HashSame = lastHash != null && lastHash == hash;
        nodeHashes.put(node, hash);
        Integer lastKeys = nodeStateKeyCounts.get(node);
        boolean dim3KeysStale = lastKeys != null && lastKeys == stateKeyCount;
        nodeStateKeyCounts.put(node, stateKeyCount);
        int visits = nodeVisits.merge(node, 1, Integer::sum);
        boolean dim4Cycle = visits >= 2;
        return dim1FpRepeat && dim2HashSame && dim3KeysStale && dim4Cycle;
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
