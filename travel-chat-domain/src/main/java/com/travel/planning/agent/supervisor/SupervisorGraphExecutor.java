package com.travel.planning.agent.supervisor;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.travel.core.guard.CircuitBreaker;
import com.travel.planning.memory.longterm.ProfileToolProvider;
import com.travel.planning.prompt.PromptTemplates;
import com.travel.planning.service.TurnCancellation;
import com.travel.planning.service.TurnInterruptedException;
import com.travel.planning.trace.TraceContext;
import com.travel.aigateway.route.ModelRoutingContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * M6-58/T9 Step4：Supervisor 阻塞整图执行器（executePlanningWithUsage 从
 * TravelSupervisorAgent 迁出）。
 *
 * <p>行为与迁移前逐字节等价：F26 整图 + 硬超时 + 唯一 threadId、F27 拦截器 token
 * 采集、F77/B4-2 整图重试、F66 直答兜底、M6-42 取消链（入口检查/拦截器短路/
 * 等待前后检查/根因解包上抛）、熔断三态降级均原样保留。</p>
 */
@Slf4j
final class SupervisorGraphExecutor {

    private final TokenUsageInterceptor tokenUsageInterceptor;
    private final CircuitBreaker.Registry circuitBreakerRegistry;
    private final PromptTemplates promptTemplates;
    private final DirectAnswerExecutor directAnswerExecutor;
    private final QuotaTripwire quotaTripwire;

    SupervisorGraphExecutor(TokenUsageInterceptor tokenUsageInterceptor,
                            CircuitBreaker.Registry circuitBreakerRegistry,
                            PromptTemplates promptTemplates,
                            DirectAnswerExecutor directAnswerExecutor,
                            QuotaTripwire quotaTripwire) {
        this.tokenUsageInterceptor = tokenUsageInterceptor;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.promptTemplates = promptTemplates;
        this.directAnswerExecutor = directAnswerExecutor;
        this.quotaTripwire = quotaTripwire;
    }

    /**
     * F27：执行行程规划并返回回答与真实 token 消耗。
     *
     * <p>token 为本次 SupervisorAgent 全部 LLM 调用（路由 + 4 个子 Agent）的
     * {@code totalTokens} 之和，经 {@link TokenUsageInterceptor} 按请求 ID 采集。
     * F64/B2：userId 写入 RunnableConfig.metadata，供画像工具（get_user_profile /
     * save_user_profile）从 ToolContext 读取，不依赖 LLM 传参。</p>
     */
    TravelSupervisorAgent.PlanningResult executePlanningWithUsage(
            SupervisorAgent supervisor, String userInput, Long userId,
            TurnCancellation cancellation) throws Exception {
        log.info("开始执行行程规划: input={}, userId={}", userInput, userId);
        long start = System.currentTimeMillis();
        CompletableFuture<Optional<OverAllState>> future = null;
        // M6-42：阻塞路径取消令牌（null 兼容；入口先检查，取消后不再发起新调用）
        TurnCancellation cancel = cancellation == null ? TurnCancellation.NOOP : cancellation;
        cancel.throwIfCancelled();
        // F89：追溯启用时复用切面生成的 requestId，与 TokenUsageInterceptor 关联
        String requestId = TraceContext.active() ? TraceContext.current().requestId
                : UUID.randomUUID().toString();
        // M7：阻塞整图在独立虚拟线程执行（ThreadLocal 不继承），先在提交线程捕获模型
        String model = ModelRoutingContext.current();
        // M8-9j：可观测性——阻塞图是否收到请求级模型
        if (model != null && !model.isBlank()) {
            log.info("[ModelRoute] 阻塞图请求级模型: requestId={}, model={}", requestId, model);
        } else {
            log.warn("[ModelRoute] 阻塞图请求级模型为空，将使用角色默认 main: requestId={}",
                    requestId);
        }
        // M8-9m：短路作用域优先轮次 key（图流重试复用），缺失回退 requestId
        String scopeKey = cancel.clientMessageId() != null && !cancel.clientMessageId().isBlank()
                ? cancel.clientMessageId() : requestId;
        tokenUsageInterceptor.begin(requestId);
        try {
            // F26 修复：必须执行 SupervisorAgent 整图（多步路由循环），
            // 而不是 supervisor.getMainAgent().call()——那只是路由 Agent，
            // 只会返回下一步子 Agent 名单（如 ["preference_analysis"]）。
            // graph.invoke 为阻塞调用且无超时参数，用 CompletableFuture 提供硬性时间边界。
            // F51：每次调用使用唯一 threadId，父图 checkpoint 按调用隔离。
            // 否则父图默认 MemorySaver + 默认 threadId 会跨调用复用子 Agent 输出
            // （TC-10 第三次只跑 preference_analysis、直接沿用上一轮 attractions/routePlan/
            // budgetEstimate，回答陈旧且与上轮完全相同）。多轮上下文由 Phase A 显式历史注入承担。
            RunnableConfig.Builder configBuilder = RunnableConfig.builder()
                    .threadId("rag_" + requestId)
                    .addMetadata(TokenUsageInterceptor.REQUEST_ID_KEY, requestId);
            ReactiveBlockSupport.addCancellationMetadata(configBuilder, cancel);
            // F64/B2：聊天链画像工具 userId 透传（metadata → ToolContext）
            if (userId != null) {
                configBuilder.addMetadata(ProfileToolProvider.USER_ID_METADATA_KEY, userId);
            }
            RunnableConfig config = configBuilder.build();
            future = CompletableFuture.supplyAsync(
                    () -> circuitBreakerRegistry.of("supervisor").call("supervisor",
                            () -> ModelRoutingContext.runWith(model,
                                    () -> invokeSupervisorSafely(supervisor, userInput, config))),
                    TravelSupervisorAgent.SUPERVISOR_EXECUTOR);
            // M6-42：等待前检查（取消后不再等待结果）
            cancel.throwIfCancelled();
            OverAllState finalState = future.orTimeout(TravelSupervisorAgent.MAX_EXECUTION_SECONDS,
                            TimeUnit.SECONDS)
                    .get()
                    .orElseThrow(() -> new IllegalStateException("Supervisor 未返回最终状态"));
            // M6-42：等待期间发生取消 → 立即终止，不进入重试/直答/落库
            cancel.throwIfCancelled();
            String result = SupervisorResponseSupport.buildFinalResponse(finalState);
            long[] mainUsage = tokenUsageInterceptor.peek(requestId);
            long totalTokens = tokenUsageInterceptor.endAndGet(requestId);
            SupervisorTraceSupport.applyTraceTokens(mainUsage);
            // F77/B4-2：路由截断/非 JSON 防护——四键全空且路由 FINISH、且疑似规划类请求时，
            // 用新 requestId 重试一次整图（F63 "playplay" 类问题：解析失败被框架当 FINISH，
            // 预算/规划被跳过）；仍空才走 F66 直答兜底。
            // F85：回顾类请求（"上次/之前安排了哪些景点"）是合法 FINISH，跳过整图重试直接直答，
            // 避免白耗一次完整图执行（F82 两档之外的第三类，见 F85 3.5 边界说明）。
            if (!SupervisorResponseSupport.hasSectionOutput(finalState)
                    && PlanningHeuristics.looksLikePlanningRequest(userInput)
                    && !PlanningHeuristics.isRecallQuery(userInput)) {
                // M6-42：整图重试前检查取消
                cancel.throwIfCancelled();
                String retryRequestId = UUID.randomUUID().toString();
                tokenUsageInterceptor.begin(retryRequestId);
                RunnableConfig.Builder retryBuilder = RunnableConfig.builder()
                        .threadId("rag_" + retryRequestId)
                        .addMetadata(TokenUsageInterceptor.REQUEST_ID_KEY, retryRequestId);
                ReactiveBlockSupport.addCancellationMetadata(retryBuilder, cancel);
                if (userId != null) {
                    retryBuilder.addMetadata(ProfileToolProvider.USER_ID_METADATA_KEY, userId);
                }
                try {
                    CompletableFuture<Optional<OverAllState>> retryFuture = CompletableFuture.supplyAsync(
                            () -> circuitBreakerRegistry.of("supervisor").call("supervisor",
                                    () -> ModelRoutingContext.runWith(model,
                                            () -> invokeSupervisorSafely(
                                                    supervisor, userInput, retryBuilder.build()))),
                            TravelSupervisorAgent.SUPERVISOR_EXECUTOR);
                    cancel.throwIfCancelled();
                    Optional<OverAllState> retried =
                            retryFuture.orTimeout(TravelSupervisorAgent.MAX_EXECUTION_SECONDS,
                                    TimeUnit.SECONDS).get();
                    cancel.throwIfCancelled();
                    if (retried.isPresent() && SupervisorResponseSupport.hasSectionOutput(retried.get())) {
                        finalState = retried.get();
                        result = SupervisorResponseSupport.buildFinalResponse(finalState);
                        long[] retryUsage = tokenUsageInterceptor.peek(retryRequestId);
                        totalTokens += tokenUsageInterceptor.endAndGet(retryRequestId);
                        SupervisorTraceSupport.applyTraceTokens(retryUsage);
                        log.info("路由重试成功: 重新生成规划, resultLength={}, tokens累计={}",
                                result != null ? result.length() : 0, totalTokens);
                    } else {
                        tokenUsageInterceptor.endAndGet(retryRequestId);
                        log.warn("路由重试仍未产生子 Agent 输出，保留原结果");
                    }
                } catch (TurnInterruptedException e) {
                    tokenUsageInterceptor.endAndGet(retryRequestId);
                    throw e;
                } catch (Exception e) {
                    tokenUsageInterceptor.endAndGet(retryRequestId);
                    log.warn("路由重试失败，保留原结果: {}", e.getMessage());
                }
            }
            // F66：非规划类问题（画像查询/闲聊等）路由直接 FINISH 且无子 Agent 输出时，
            // 用主模型基于组合输入直答，避免返回兜底"抱歉，未能生成行程规划"。
            // （20:18 实测"我的旅行画像里有什么？"→ supervisor_next=[FINISH]，preference/attractions
            //  /routePlan/budgetEstimate 全为 0，B2 画像问答不可达。）
            if (!SupervisorResponseSupport.hasSectionOutput(finalState)) {
                try {
                    // M6-42：直答兜底前检查取消（不再发起新的 LLM 调用）
                    cancel.throwIfCancelled();
                    // F85：直答兜底从裸输入升级为"system 指令 + 输入"双消息——
                    // 回顾类用回顾专用指令（不得编造景点），其余用覆盖优先级指令
                    // （会话 feedback/最新确认 > constraint > 画像）。
                    String system;
                    if (PlanningHeuristics.isRecallQuery(userInput)) {
                        system = promptTemplates.directRecallSystem();
                    } else {
                        system = promptTemplates.directAnswerSystem();
                    }
                    ChatResponse direct = directAnswerExecutor.callDirect(system, userInput, false);
                    String text = direct.getResult() != null && direct.getResult().getOutput() != null
                            ? direct.getResult().getOutput().getText() : null;
                    if (text != null && !text.isBlank()) {
                        result = text.trim();
                        if (direct.getMetadata() != null && direct.getMetadata().getUsage() != null) {
                            Usage u = direct.getMetadata().getUsage();
                            totalTokens += u.getTotalTokens() != null ? u.getTotalTokens() : 0;
                            SupervisorTraceSupport.applyTraceTokens(new long[]{
                                    u.getPromptTokens() != null ? u.getPromptTokens() : 0,
                                    u.getCompletionTokens() != null ? u.getCompletionTokens() : 0,
                                    u.getTotalTokens() != null ? u.getTotalTokens() : 0});
                        }
                        log.info("非规划类问题直答兜底: inputLength={}, answerLength={}",
                                userInput.length(), result.length());
                    }
                } catch (TurnInterruptedException e) {
                    // M6-42：中断终止必须上抛，不得被直答兜底吞掉
                    throw e;
                } catch (Exception e) {
                    log.warn("非规划类问题直答兜底失败，保留原结果: {}", e.getMessage());
                }
            }
            long cost = System.currentTimeMillis() - start;
            log.info("行程规划完成, 耗时={}ms, 结果长度={}, supervisor_next={}, "
                            + "输出: preference={}, attractions={}, routePlan={}, budgetEstimate={}, tokens={}",
                    cost, result != null ? result.length() : 0,
                    finalState.value("supervisor_next").orElse("N/A"),
                    SupervisorResponseSupport.textLen(finalState, "preference"),
                    SupervisorResponseSupport.textLen(finalState, "attractions"),
                    SupervisorResponseSupport.textLen(finalState, "routePlan"),
                    SupervisorResponseSupport.textLen(finalState, "budgetEstimate"),
                    totalTokens);
            SupervisorTraceSupport.applyTracePath(finalState);
            // M8-9：最终 state 的 routePlan JSON 随结果返回（供会话知识 itinerary_day 切片写入）
            String routePlanJson = SupervisorResponseSupport.toText(finalState.value("routePlan"));
            return new TravelSupervisorAgent.PlanningResult(result, totalTokens, routePlanJson);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            // M6-42：拦截器取消短路抛出的 TurnInterruptedException 可能被图执行器
            // 包装多层，先根因解包并原样上抛（中断不落库、不转兜底）
            if (ReactiveBlockSupport.findRootCause(cause) instanceof TurnInterruptedException tie) {
                throw tie;
            }
            if (cause instanceof TimeoutException) {
                if (future != null) {
                    future.cancel(true);
                }
                log.error("行程规划超时（超过 {} 秒）", TravelSupervisorAgent.MAX_EXECUTION_SECONDS);
                throw new IllegalStateException(
                        "行程规划超时（超过 " + TravelSupervisorAgent.MAX_EXECUTION_SECONDS + " 秒），请稍后重试", e);
            }
            if (cause instanceof RuntimeException re) {
                if (cause instanceof CircuitBreaker.CircuitOpenException) {
                    log.warn("[CircuitBreaker] 熔断中，拒绝执行: {}", cause.getMessage());
                    throw new IllegalStateException("服务繁忙，请稍后重试", cause);
                }
                throw re;
            }
            throw new IllegalStateException("行程规划执行失败", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (future != null) {
                future.cancel(true);
            }
            throw new IllegalStateException("行程规划被中断", e);
        } finally {
            tokenUsageInterceptor.endAndGet(requestId);
            // M8-9m：请求结束清理额度短路状态（与 token 采集 endAndGet 对称）
            quotaTripwire.clear(scopeKey);
        }
    }

    /**
     * 包装 {@code supervisor.invoke}：{@code GraphRunnerException} 为受检异常，
     * 无法直接在 CompletableFuture.supplyAsync 的 lambda 中抛出，统一转为
     * RuntimeException，由 executePlanning 的 ExecutionException 分支解包处理。
     */
    private static Optional<OverAllState> invokeSupervisorSafely(
            SupervisorAgent supervisor, String userInput, RunnableConfig config) {
        try {
            return supervisor.invoke(userInput, config);
        } catch (Exception e) {
            throw new RuntimeException("Supervisor 执行失败", e);
        }
    }
}
