package com.travel.planning.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.travel.common.exception.ItineraryGenerationException;
import com.travel.planning.agent.supervisor.TokenUsageInterceptor;
import com.travel.planning.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * M10-1c：行程工作流图执行器（自 ItineraryService 拆出）。
 *
 * <p>职责：StateGraph 阻塞执行统一入口（orTimeout + cancel + 中断语义）与
 * 图外辅助 LLM 调用的硬超时包装（F23/F24/M4-7 修复 4 语义原样保留）。</p>
 */
@Slf4j
@Component
public class ItineraryGraphExecutor {

    /** 工作流整体执行超时（秒）：硬性退出边界。 */
    public static final long MAX_EXECUTION_SECONDS = 300;

    private final TokenUsageInterceptor tokenUsageInterceptor;

    public ItineraryGraphExecutor(TokenUsageInterceptor tokenUsageInterceptor) {
        this.tokenUsageInterceptor = tokenUsageInterceptor;
    }

    /**
     * 工作流执行专用线程池：使用虚拟线程（Java 21），daemon 且轻量，
     * 与 CompletableFuture.cancel(true) 配合可及时中断 graph.invoke 的阻塞等待。
     */
    private static final ExecutorService WORKFLOW_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    /**
     * M4-8：图执行统一入口（orTimeout 300s + cancel 中断，F23/F24 语义不变）。
     */
    public OverAllState execute(CompiledGraph graph, Map<String, Object> initialState) {
        CompletableFuture<Optional<OverAllState>> future = null;
        // M14-1c：行程图与 Supervisor 同款 token 采集——TraceAspect 已为
        // ItineraryService.generate 建 requestId，这里 begin/end 同一拦截器并回写 Holder
        String requestId = TraceContext.active()
                ? TraceContext.current().requestId : null;
        boolean collectTokens = requestId != null && !requestId.isBlank()
                && tokenUsageInterceptor != null;
        if (collectTokens) {
            tokenUsageInterceptor.begin(requestId);
        }
        try {
            // F64/B2：把 userId 写入 RunnableConfig.metadata，供画像工具从 ToolContext 读取
            Object uid = initialState.get("userId");
            RunnableConfig.Builder configBuilder = RunnableConfig.builder()
                    .addMetadata(com.travel.planning.memory.longterm.ProfileToolProvider.USER_ID_METADATA_KEY,
                            uid == null ? 0L : uid)
                    // M6-51：AgentLlmNode 默认按 metadata("_stream_") 走流式（输出 Flux），
                    // 导致 SnapshotNodeWrapper 快照 payload 泄漏为 "FluxFlatMap"（toString）。
                    // 显式关闭流式 → 节点输出 AssistantMessage，快照可正确归一化业务 JSON。
                    .addMetadata("_stream_", false);
            if (collectTokens) {
                configBuilder.addMetadata(TokenUsageInterceptor.REQUEST_ID_KEY, requestId);
            }
            RunnableConfig config = configBuilder.build();
            future = CompletableFuture.supplyAsync(
                    () -> graph.invoke(initialState, config), WORKFLOW_EXECUTOR);
            return future.orTimeout(MAX_EXECUTION_SECONDS, TimeUnit.SECONDS)
                    .get()
                    .orElseThrow(() -> new ItineraryGenerationException("工作流未返回最终状态"));
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof TimeoutException) {
                // F24 补强：超时后立即 cancel(true) 中断后台 graph.invoke
                if (future != null) {
                    future.cancel(true);
                }
                log.error("工作流执行超时（>{}s）", MAX_EXECUTION_SECONDS);
                throw new ItineraryGenerationException(
                        "行程生成超时（超过 " + MAX_EXECUTION_SECONDS + " 秒），请稍后重试", ee);
            }
            if (cause instanceof RuntimeException re) {
                throw re; // 保留原始异常（含 DashScope 上游错误）
            }
            throw new ItineraryGenerationException(
                    cause != null ? cause.getMessage() : "行程生成失败", ee);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ItineraryGenerationException("行程生成被中断", ie);
        } finally {
            if (collectTokens) {
                long[] usage = tokenUsageInterceptor.peek(requestId);
                tokenUsageInterceptor.endAndGet(requestId);
                if (TraceContext.active()
                        && requestId.equals(TraceContext.current().requestId)) {
                    TraceContext.Holder h = TraceContext.current();
                    h.promptTokens += usage[0];
                    h.completionTokens += usage[1];
                    h.totalTokens += usage[2];
                }
                log.info("[ItineraryTrace] itinerary 图 token 采集: requestId={}, "
                                + "prompt={}, completion={}, total={}",
                        requestId, usage[0], usage[1], usage[2]);
            }
        }
    }

    /**
     * M4-7（修复 4）：带硬超时执行辅助 LLM 调用（虚拟线程 + orTimeout + cancel）。
     * 与主工作流同样的超时语义（F23/F24）。
     */
    public <T> T withTimeout(Supplier<T> task, long seconds, String what) {
        CompletableFuture<T> future = CompletableFuture.supplyAsync(task, WORKFLOW_EXECUTOR);
        try {
            return future.orTimeout(seconds, TimeUnit.SECONDS).get();
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof TimeoutException) {
                future.cancel(true);
                throw new ItineraryGenerationException(what + "超时（超过 " + seconds + " 秒）", ee);
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new ItineraryGenerationException(
                    what + "失败: " + (cause != null ? cause.getMessage() : "unknown"), ee);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ItineraryGenerationException(what + "被中断", ie);
        }
    }
}
