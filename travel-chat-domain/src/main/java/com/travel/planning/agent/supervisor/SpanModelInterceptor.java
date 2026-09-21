package com.travel.planning.agent.supervisor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.travel.common.trace.SpanCollector;
import com.travel.common.trace.SpanCollector.Span;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * S-B6b：图流子代理 Span 拦截器（t_agent_trace.spans 的图流段数据源）。
 *
 * <p>跨线程关联：requestId 经 {@code ModelRequest.getContext()} 的
 * {@link TokenUsageInterceptor#REQUEST_ID_KEY} **显式传递**（SupervisorGraphExecutor/
 * SupervisorStreamExecutor addMetadata 既有通道）——不读任何 ThreadLocal（E-48 红线：
 * 图流子代理跑在虚拟线程池，同线程 MDC 上下文会静默断链，显式传递是唯一合规路径；
 * 与 QuotaShortCircuitInterceptor.scopeOf 同款先例）。</p>
 *
 * <p>流式语义：Flux 响应的 span 在 doOnComplete/doOnError/doOnCancel 终态各自
 * 恰好一次收口（AtomicBoolean 保证）；异常路径同步收口后原样上抛。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Component
@RequiredArgsConstructor
public class SpanModelInterceptor extends ModelInterceptor {

    private final SpanCollector spanCollector;

    @Override
    public String getName() {
        return "spanModelInterceptor";
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        if (request == null || request.getContext() == null) {
            return handler.call(request);
        }
        Object rid = request.getContext().get(TokenUsageInterceptor.REQUEST_ID_KEY);
        if (!(rid instanceof String requestId) || requestId.isBlank()) {
            return handler.call(request);
        }
        Span span = spanCollector.startSpan(requestId, "llm", "llm");
        try {
            ModelResponse response = handler.call(request);
            if (response != null && response.getMessage() instanceof Flux<?> flux) {
                AtomicBoolean ended = new AtomicBoolean(false);
                return new ModelResponse(flux
                        .doOnComplete(() -> endOnce(requestId, span, ended, "ok", null))
                        .doOnError(e -> endOnce(requestId, span, ended, "error",
                                Map.of("error", String.valueOf(e.getMessage()))))
                        .doOnCancel(() -> endOnce(requestId, span, ended, "cancelled", null)));
            }
            spanCollector.endSpan(requestId, span, "ok", null);
            return response;
        } catch (RuntimeException e) {
            spanCollector.endSpan(requestId, span, "error",
                    Map.of("error", String.valueOf(e.getMessage())));
            throw e;
        }
    }

    /** 流式三终态恰好一次收口（complete/error/cancel 互斥，AtomicBoolean 防重复入队） */
    private void endOnce(String requestId, Span span, AtomicBoolean ended,
                         String status, Map<String, Object> attrs) {
        if (ended.compareAndSet(false, true)) {
            spanCollector.endSpan(requestId, span, status, attrs);
        }
    }
}
