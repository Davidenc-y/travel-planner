package com.travel.knowledge.rag;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * RAG 路由指标（F44/P3：路由可观测，供 auto 路由 A/B 对比）。
 *
 * <p>指标：rag.routing.total / rag.routing.by_router / rag.routing.by_strategy /
 * rag.routing.elapsed（毫秒直方图），可通过 Actuator /metrics 查看。</p>
 */
@Component
public class RagRoutingMetrics {

    private final MeterRegistry registry;
    private final Counter total;
    private final Timer elapsed;

    public RagRoutingMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.total = Counter.builder("rag.routing.total")
                .description("RAG 路由总请求数")
                .register(registry);
        this.elapsed = Timer.builder("rag.routing.elapsed")
                .description("RAG 路由耗时")
                .publishPercentileHistogram()
                .register(registry);
    }

    /**
     * 记录一次路由：总数 +1、按 router/strategy 分桶计数、耗时直方图
     */
    public void record(String router, String strategy, long elapsedMs) {
        total.increment();
        Counter.builder("rag.routing.by_router")
                .tag("router", router)
                .register(registry)
                .increment();
        Counter.builder("rag.routing.by_strategy")
                .tag("strategy", strategy)
                .register(registry)
                .increment();
        elapsed.record(elapsedMs, TimeUnit.MILLISECONDS);
    }
}
