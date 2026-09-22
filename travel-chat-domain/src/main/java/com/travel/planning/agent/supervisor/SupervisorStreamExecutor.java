package com.travel.planning.agent.supervisor;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.travel.core.guard.CircuitBreaker;
import com.travel.aigateway.route.ModelRoutingContext;
import com.travel.planning.agent.support.DestinationContext;
import com.travel.planning.memory.knowledge.SupervisorResultLedger;
import com.travel.planning.memory.longterm.ProfileToolProvider;
import com.travel.memory.prompt.PromptTemplates;
import com.travel.stream.service.TurnCancellation;
import com.travel.stream.service.TurnInterruptedException;
import com.travel.planning.agent.supervisor.support.ActionFingerprinter;
import com.travel.planning.trace.TtftChannel;
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
    /** T-5a：子代理结果台账（T-5b 捕获/清理；构造注入——本类非 Spring 托管） */
    private final SupervisorResultLedger resultLedger;
    /** T-5b：台账开关（false=零捕获零清理，行为等价现状） */
    private final boolean resumeLedgerEnabled;

    SupervisorStreamExecutor(TokenUsageInterceptor tokenUsageInterceptor,
                             CircuitBreaker.Registry circuitBreakerRegistry,
                             PromptTemplates promptTemplates,
                             DirectAnswerExecutor directAnswerExecutor,
                             QuotaTripwire quotaTripwire,
                            PlanningHeuristics planningHeuristics,
                            SupervisorResultLedger resultLedger,
                            boolean resumeLedgerEnabled) {
        this.tokenUsageInterceptor = tokenUsageInterceptor;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.promptTemplates = promptTemplates;
        this.directAnswerExecutor = directAnswerExecutor;
        this.quotaTripwire = quotaTripwire;
        this.planningHeuristics = planningHeuristics;
        this.resultLedger = resultLedger;
        this.resumeLedgerEnabled = resumeLedgerEnabled;
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
            SupervisorAgent supervisor, String userInput, Long userId, String sessionId,
            BiConsumer<String, String> nodeThinking,
            Consumer<String> tokenSink,
            TurnCancellation cancellation) throws Exception {
        return streamPlanningWithUsage(supervisor, userInput, userId, sessionId,
                nodeThinking, tokenSink, cancellation, null);
    }

    /** T-5c：resumeSeed 非空时走框架 Map 入口种子化 state（DedupSubAgentHook 既有语义自动短路）。 */
    TravelSupervisorAgent.StreamPlanningResult streamPlanningWithUsage(
            SupervisorAgent supervisor, String userInput, Long userId, String sessionId,
            BiConsumer<String, String> nodeThinking,
            Consumer<String> tokenSink,
            TurnCancellation cancellation,
            Map<String, String> resumeSeed) throws Exception {
        log.info("开始执行行程规划(图流): input={}, userId={}", userInput, userId);
        long start = System.currentTimeMillis();
        // U-1b 分支A：thinking 回调首响等价打点（firstOutputAt 恒 0 时的 fallback 源）。
        // 包装空安全——nodeThinking 缺位时仍打点：TTFT 属内部进度度量，不依赖 SSE 消费者在否
        long[] thinkingFirstAt = {0};
        BiConsumer<String, String> wrappedThinking = wrapThinkingStamp(nodeThinking, thinkingFirstAt);
        TurnCancellation cancel = cancellation == null ? TurnCancellation.NOOP : cancellation;
        String requestId = TraceContext.active() ? TraceContext.current().requestId
                : UUID.randomUUID().toString();
        // M8-9m：短路作用域优先轮次 key（图流重试复用），缺失回退 requestId
        String scopeKey = cancel.clientMessageId() != null && !cancel.clientMessageId().isBlank()
                ? cancel.clientMessageId() : requestId;
        // T-5d：复用 thinking 事件（流起始逐复用代理发，前端思考流与实际跳过对齐——
        // 用户点名的展示不一致修复点；文案=E-47 授权新增"复用中断前结果："）
        emitReuseThinking(wrappedThinking, resumeSeed);
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
            // T-1：锚定目的地经 metadata 跨线程传递（调用线程读 DestinationContext，E-48 合规）
            String destination = DestinationContext.routed();
            if (destination != null && !destination.isBlank()) {
                configBuilder.addMetadata(DestinationContext.DESTINATION_METADATA_KEY, destination);
            }
            RunnableConfig config = configBuilder.build();
            AtomicReference<OverAllState> lastState = new AtomicReference<>();
            long[] nodeTokens = {0};
            String[] lastNodeLabel = {null};
            Flux<NodeOutput> flux = circuitBreakerRegistry.of("supervisor").call(
                    "supervisor", () -> streamSupervisorSafely(supervisor, userInput,
                            resumeSeed, config));
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
                        captureNodeOutput(sessionId, cancel.clientMessageId(),
                                out.node(), outputText, os); // T-5b（审计修复：按 outputKey 轮询 state）
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
                if (label != null && !label.equals(lastNodeLabel[0])) {
                    lastNodeLabel[0] = label;
                    // U-1b：经包装消费者发射（首响打点）；wrapped 恒非空且空安全，原 nodeThinking!=null 守卫内移
                    wrappedThinking.accept("routing", label);
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
            // U-1a：图流 TTFT 恒空诊断——审计实弹解读 firstOutputAt 是否恒 0（恒 0=流未发任何 NodeOutput 事件）；
            // 置于 record 判定块之前，firstOutputAt=0 的失败形态也会打出日志
            log.info("[TTFT-DIAG] firstOutputAt={}, start={}, elapsed={}",
                    firstOutputAt[0], start, System.currentTimeMillis() - start);
            // U-1b：TTFT 双源兜底（NodeOutput 优先 + thinking 首回调 fallback；-1=双源皆缺不写）
            long ttft = resolveTtft(firstOutputAt[0], thinkingFirstAt[0], start);
            if (ttft >= 0) {
                SupervisorTraceSupport.applyTraceTtft(ttft); // S-B7 holder 写保留（T-3a 双写设计不变）
                TtftChannel.record(requestId, ttft); // T-3a：通道兜底双写（putIfAbsent first-wins）
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
                    String retryDestination = DestinationContext.routed();
                    if (retryDestination != null && !retryDestination.isBlank()) {
                        retryBuilder.addMetadata(DestinationContext.DESTINATION_METADATA_KEY,
                                retryDestination);
                    }
                    AtomicReference<OverAllState> retryState = new AtomicReference<>();
                    long[] retryNodeTokens = {0};
                    Flux<NodeOutput> retryFlux = circuitBreakerRegistry.of("supervisor").call(
                            "supervisor", () -> streamSupervisorSafely(
                                    supervisor, userInput, resumeSeed, retryBuilder.build()));
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
            // T-5b：轮次正常完成 best-effort 单清（防陈旧；中断/异常上抛不经过此处
            // =台账保留供同键重试种子；fail-open 在 ledger 内）
            String turnKey = cancel.clientMessageId();
            if (resultLedger != null && sessionId != null && !sessionId.isBlank()
                    && turnKey != null && !turnKey.isBlank()) {
                resultLedger.clear(sessionId, turnKey);
            }
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

    /**
     * T-5d：流起始逐复用代理发 thinking 事件——固定图执行顺序（偏好→景点→路线→预算）
     * 只发种子命中的键；nodeThinking 空安全。包级=单测直连。
     */
    static void emitReuseThinking(BiConsumer<String, String> nodeThinking,
                                  Map<String, String> resumeSeed) {
        if (nodeThinking == null || resumeSeed == null || resumeSeed.isEmpty()) {
            return;
        }
        for (String key : java.util.List.of("preference", "attractions", "routePlan",
                "budgetEstimate")) {
            if (!resumeSeed.containsKey(key)) {
                continue;
            }
            String name = friendlyOutputKey(key);
            if (name != null) {
                nodeThinking.accept("routing", "复用中断前结果：" + name);
            }
        }
    }

    /**
     * U-1b：TTFT 三态解析——NodeOutput 首事件优先，其次 thinking 首回调 fallback，
     * 双源皆缺=-1（调用方 ttft>=0 才写）。包级=单测直连（S-C1 isOscillating 先例）。
     */
    static long resolveTtft(long firstOutputAt, long thinkingFirstAt, long start) {
        if (firstOutputAt > 0) {
            return firstOutputAt - start;
        }
        if (thinkingFirstAt > 0) {
            return thinkingFirstAt - start;
        }
        return -1;
    }

    /**
     * U-1b：thinking 首响打点包装——首次 accept 记 thinkingFirstAt（first-wins 不被后续覆盖），
     * 委托空安全（nodeThinking 缺位时仅打点不外发）。包级=单测直连。
     */
    static BiConsumer<String, String> wrapThinkingStamp(BiConsumer<String, String> delegate,
                                                        long[] thinkingFirstAt) {
        return (stage, msg) -> {
            if (thinkingFirstAt[0] == 0) {
                thinkingFirstAt[0] = System.currentTimeMillis();
            }
            if (delegate != null) {
                delegate.accept(stage, msg);
            }
        };
    }

    /** T-5d：outputKey→中文友好名（未知键返回 null 跳过）。 */
    static String friendlyOutputKey(String outputKey) {
        if (outputKey == null) {
            return null;
        }
        return switch (outputKey) {
            case "preference" -> "偏好分析";
            case "attractions" -> "景点筛选";
            case "routePlan" -> "路线安排";
            case "budgetEstimate" -> "预算估算";
            default -> null;
        };
    }

    /**
     * T-5b：子代理节点输出捕获台账（开关∧node∈四映射∧sessionId/clientMessageId 齐
     * ∧文本非空；包级可见=单测直连）。clientMessageId 空（无幂等键轮次）跳过——
     * 台账按 {sessionId}:{clientMessageId} 键控，无键轮次无从重试。
     */
    void captureNodeOutput(String sessionId, String clientMessageId,
                           String node, String outputText, OverAllState state) {
        if (!resumeLedgerEnabled || resultLedger == null
                || sessionId == null || sessionId.isBlank()
                || clientMessageId == null || clientMessageId.isBlank()) {
            return;
        }
        // 审计修复（2026-09-22 实弹）：框架 NodeOutput.node() 名称带包装前缀（friendlyNode
        // 只能用 contains 的原因），精确 Map.get(node) 恒 null=原实现死代码；且子代理输出
        // state 键是 outputKey（preference 等）不是节点名。改为按 outputKey 轮询 state：
        // 哪个键当前非空即记哪个（幂等：record 覆盖同 field，已完成子代理重复节点事件无害）
        // 审计修复二段：外层流只发包装节点（travel_planning_supervisor_supervisor 等），
        // 子代理节点不独立出现——去掉节点名门控，纯按 outputKey 轮询（state 随 supervisor
        // 节点增量 yield 而累积，轮询本身已足够精确且幂等）
        for (String outputKey : SupervisorResultLedger.NODE_TO_OUTPUT_KEY.values()) {
            String text = SupervisorResponseSupport.toText(state.value(outputKey));
            if (text != null && !text.isBlank()) {
                resultLedger.record(sessionId, clientMessageId, outputKey, text);
            }
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
            SupervisorAgent supervisor, String userInput, Map<String, String> resumeSeed,
            RunnableConfig config) {
        try {
            if (resumeSeed != null && !resumeSeed.isEmpty()) {
                return supervisor.stream(buildSeededInput(userInput, resumeSeed), config);
            }
            return supervisor.stream(userInput, config);
        } catch (Exception e) {
            throw new RuntimeException("Supervisor 图流失败", e);
        }
    }

    /**
     * T-5c：种子 input map——outputKey=文本 逐键 + "input"=用户输入（框架 String
     * 路径的 state 键名假设，实弹验收⑤覆盖；不符时台账回合回退 full-rerun）。
     * 包级=单测直连。javap 实证框架 Agent.stream(Map, RunnableConfig) 重载存在。
     */
    static Map<String, Object> buildSeededInput(String userInput, Map<String, String> resumeSeed) {
        Map<String, Object> seeded = new java.util.HashMap<>(resumeSeed);
        seeded.put("input", userInput);
        return seeded;
    }
}
