package com.travel.knowledge.rag.support;

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
    /** M4-6：Rerank 指标（total/elapsed/fallback，风格对齐 routing） */
    private final Counter rerankTotal;
    private final Timer rerankElapsed;
    private final Counter rerankFallback;

    public RagRoutingMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.total = Counter.builder("rag.routing.total")
                .description("RAG 路由总请求数")
                .register(registry);
        this.elapsed = Timer.builder("rag.routing.elapsed")
                .description("RAG 路由耗时")
                .publishPercentileHistogram()
                .register(registry);
        this.rerankTotal = Counter.builder("rag.rerank.total")
                .description("Rerank 调用总次数（含 noop 直通）")
                .register(registry);
        this.rerankElapsed = Timer.builder("rag.rerank.elapsed")
                .description("Rerank 调用耗时")
                .publishPercentileHistogram()
                .register(registry);
        this.rerankFallback = Counter.builder("rag.rerank.fallback")
                .description("Rerank fail-open 次数（失败按原顺序截断）")
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

    /**
     * M4-6：记录一次 Rerank 调用（总数 + 耗时直方图）。
     */
    public void recordRerank(long elapsedMs) {
        rerankTotal.increment();
        rerankElapsed.record(elapsedMs, TimeUnit.MILLISECONDS);
    }

    /**
     * M4-6：记录一次 Rerank fail-open（失败降级计数）。
     */
    public void recordRerankFallback() {
        rerankFallback.increment();
    }
}
