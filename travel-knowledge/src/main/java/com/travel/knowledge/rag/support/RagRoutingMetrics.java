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
    /** M8-2：检索降级计数（es_fail / milvus_fail / empty_relax / enrich_fail） */
    private final Counter degraded;
    /** AF-2a：Tavily 上游抖动观测（G2：SNI 选择性阻断→IOException 重试一次，E-52） */
    private final Counter tavilyRetryTotal;
    private final Counter tavilyRetryRecovered;
    /** AJ-1b：QU 意图缓存命中观测（G3 扩容前埋点；常开无键） */
    private final Counter quCacheHit;
    private final Counter quCacheMiss;
    /** AK-3c：QU 意图缓存驱逐观测（cacheSize 裁定直接证据：驱逐率>阈值才扩容；常开无键） */
    private final Counter quCacheEvict;
    /** AN-1：QU LLM 并发帽拒绝观测（信号量 tryAcquire 失败 fail-open；常开无键） */
    private final Counter quOverflow;
    /** AN-1：QU LLM 抽取超时观测（限时 get 超时 fail-open；常开无键） */
    private final Counter quTimeout;
    /** AO-1a：查询向量缓存命中观测（rag.embedding.cache_hit；常开无键） */
    private final Counter embeddingCacheHit;
    /** AO-1a：查询向量缓存未命中观测（rag.embedding.cache_miss；常开无键） */
    private final Counter embeddingCacheMiss;
    /** AO-1b：embedding API 调用观测（rag.embedding.calls；BudgetGuard 只计 chat 的计量盲区修正） */
    private final Counter embeddingCalls;
    /** AO-1b：embedding 并发帽拒绝观测（rag.embedding.overflow；常开无键） */
    private final Counter embeddingOverflow;
    /** AO-1b：embedding 调用超时观测（rag.embedding.timeout；常开无键） */
    private final Counter embeddingTimeout;
    /** AO-1c：hyde 缓存命中观测（rag.hyde.cache_hit；三级缓存链中间级，常开无键） */
    private final Counter hydeCacheHit;
    /** AO-1c：hyde 缓存未命中观测（rag.hyde.cache_miss；常开无键） */
    private final Counter hydeCacheMiss;

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
        this.degraded = Counter.builder("rag.routing.degraded")
                .description("检索链路降级次数（按 reason 分桶）")
                .register(registry);
        this.tavilyRetryTotal = Counter.builder("rag.tavily.retry.total")
                .description("Tavily IOException 重试次数")
                .register(registry);
        this.tavilyRetryRecovered = Counter.builder("rag.tavily.retry.recovered")
                .description("Tavily 重试恢复次数（重发 send 成功口径）")
                .register(registry);
        this.quCacheHit = Counter.builder("rag.qu.cache.hit")
                .description("QU 意图缓存命中次数")
                .register(registry);
        this.quCacheMiss = Counter.builder("rag.qu.cache.miss")
                .description("QU 意图缓存未命中次数")
                .register(registry);
        this.quCacheEvict = Counter.builder("rag.qu.cache.evict")
                .description("QU 意图缓存 LRU 驱逐次数")
                .register(registry);
        this.quOverflow = Counter.builder("rag.qu.overflow")
                .description("QU LLM 并发帽拒绝次数（信号量满 fail-open）")
                .register(registry);
        this.quTimeout = Counter.builder("rag.qu.timeout")
                .description("QU LLM 抽取超时次数（限时 get fail-open）")
                .register(registry);
        this.embeddingCacheHit = Counter.builder("rag.embedding.cache_hit")
                .description("查询向量缓存命中次数（AO-1a，同文本零 API 复用）")
                .register(registry);
        this.embeddingCacheMiss = Counter.builder("rag.embedding.cache_miss")
                .description("查询向量缓存未命中次数（AO-1a，含 TTL 过期重嵌）")
                .register(registry);
        this.embeddingCalls = Counter.builder("rag.embedding.calls")
                .description("embedding API 调用次数（AO-1b，BudgetGuard 只计 chat 的计量盲区修正）")
                .register(registry);
        this.embeddingOverflow = Counter.builder("rag.embedding.overflow")
                .description("embedding 并发帽拒绝次数（AO-1b，信号量满 bm25-only 降级）")
                .register(registry);
        this.embeddingTimeout = Counter.builder("rag.embedding.timeout")
                .description("embedding 调用超时次数（AO-1b，限时 get bm25-only 降级）")
                .register(registry);
        this.hydeCacheHit = Counter.builder("rag.hyde.cache_hit")
                .description("hyde 缓存命中次数（AO-1c，命中零 LLM）")
                .register(registry);
        this.hydeCacheMiss = Counter.builder("rag.hyde.cache_miss")
                .description("hyde 缓存未命中次数（AO-1c）")
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
     * AP-A1：dispatch 层路由计数（rag.dispatch.route，tag=strategy 实名；TTL 命中路径记
     * "cache_hit"——该路径此前提前 return 旁路 record() 全部观测，AR-10 盲区修正；常开无键）。
     */
    public void recordDispatchRoute(String strategy) {
        Counter.builder("rag.dispatch.route")
                .tag("strategy", strategy)
                .register(registry)
                .increment();
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

    /**
     * M8-2：记录一次检索链路降级事件（reason：es_fail/milvus_fail/empty_relax/enrich_fail）。
     *
     * <p>背景：检索失败静默降级空数组时系统「看起来正常」，LLM 实际处于无约束编造状态；
     * 本计数让降级事件可观测（Actuator /metrics + 日志），与 t_agent_trace DEGRADED 互补。</p>
     */
    public void recordDegraded(String reason) {
        Counter.builder("rag.routing.degraded")
                .tag("reason", reason)
                .register(registry)
                .increment();
        degraded.increment();
    }

    /** AF-2a：记录一次 Tavily IOException 重试（重发前计数，recordRerank 同款模式）。 */
    public void recordTavilyRetry() {
        tavilyRetryTotal.increment();
    }

    /** AF-2a：记录一次 Tavily 重试恢复（重发 send 成功口径，send 后 2xx/解析成败不再区分）。 */
    public void recordTavilyRetryRecovered() {
        tavilyRetryRecovered.increment();
    }

    /** AF-2a：重试计数读取（观测/单测断言面）。 */
    public double tavilyRetryTotalCount() {
        return tavilyRetryTotal.count();
    }

    public double tavilyRetryRecoveredCount() {
        return tavilyRetryRecovered.count();
    }

    /** AJ-1b：记录一次 QU 意图缓存命中（观测面，常开）。 */
    public void recordQuCacheHit() {
        quCacheHit.increment();
    }

    /** AJ-1b：记录一次 QU 意图缓存未命中（观测面，常开）。 */
    public void recordQuCacheMiss() {
        quCacheMiss.increment();
    }

    /** AK-3c：记录一次 QU 意图缓存 LRU 驱逐（removeEldestEntry 判真时；观测面，常开）。 */
    public void recordQuCacheEvict() {
        quCacheEvict.increment();
    }

    /** AJ-1b：命中计数读取（观测/单测断言面，tavilyRetryTotalCount 同款）。 */
    public double quCacheHitCount() {
        return quCacheHit.count();
    }

    /** AJ-1b：未命中计数读取（观测/单测断言面）。 */
    public double quCacheMissCount() {
        return quCacheMiss.count();
    }

    /** AK-3c：驱逐计数读取（观测/单测断言面）。 */
    public double quCacheEvictCount() {
        return quCacheEvict.count();
    }

    /** AN-1：记录一次 QU LLM 并发帽拒绝（rag.qu.overflow，观测面，常开）。 */
    public void recordQuOverflow() {
        quOverflow.increment();
    }

    /** AN-1：记录一次 QU LLM 抽取超时（rag.qu.timeout，观测面，常开）。 */
    public void recordQuTimeout() {
        quTimeout.increment();
    }

    /** AN-1：并发帽拒绝计数读取（观测/单测断言面）。 */
    public double quOverflowCount() {
        return quOverflow.count();
    }

    /** AN-1：超时计数读取（观测/单测断言面）。 */
    public double quTimeoutCount() {
        return quTimeout.count();
    }

    /** AO-1a：记录一次查询向量缓存命中（rag.embedding.cache_hit，观测面，常开）。 */
    public void recordEmbeddingCacheHit() {
        embeddingCacheHit.increment();
    }

    /** AO-1a：记录一次查询向量缓存未命中（rag.embedding.cache_miss，观测面，常开）。 */
    public void recordEmbeddingCacheMiss() {
        embeddingCacheMiss.increment();
    }

    /** AO-1a：向量缓存命中计数读取（观测/单测断言面）。 */
    public double embeddingCacheHitCount() {
        return embeddingCacheHit.count();
    }

    /** AO-1a：向量缓存未命中计数读取（观测/单测断言面）。 */
    public double embeddingCacheMissCount() {
        return embeddingCacheMiss.count();
    }

    /** AO-1b：记录一次 embedding API 调用（rag.embedding.calls，观测面，常开）。 */
    public void recordEmbeddingCall() {
        embeddingCalls.increment();
    }

    /** AO-1b：记录一次 embedding 并发帽拒绝（rag.embedding.overflow，观测面，常开）。 */
    public void recordEmbeddingOverflow() {
        embeddingOverflow.increment();
    }

    /** AO-1b：记录一次 embedding 调用超时（rag.embedding.timeout，观测面，常开）。 */
    public void recordEmbeddingTimeout() {
        embeddingTimeout.increment();
    }

    /** AO-1b：embedding 调用计数读取（观测/单测断言面）。 */
    public double embeddingCallsCount() {
        return embeddingCalls.count();
    }

    /** AO-1b：并发帽拒绝计数读取（观测/单测断言面）。 */
    public double embeddingOverflowCount() {
        return embeddingOverflow.count();
    }

    /** AO-1b：超时计数读取（观测/单测断言面）。 */
    public double embeddingTimeoutCount() {
        return embeddingTimeout.count();
    }

    /** AO-1c：记录一次 hyde 缓存命中（rag.hyde.cache_hit，观测面，常开）。 */
    public void recordHydeCacheHit() {
        hydeCacheHit.increment();
    }

    /** AO-1c：记录一次 hyde 缓存未命中（rag.hyde.cache_miss，观测面，常开）。 */
    public void recordHydeCacheMiss() {
        hydeCacheMiss.increment();
    }

    /** AO-1c：hyde 缓存命中计数读取（观测/单测断言面）。 */
    public double hydeCacheHitCount() {
        return hydeCacheHit.count();
    }

    /** AO-1c：hyde 缓存未命中计数读取（观测/单测断言面）。 */
    public double hydeCacheMissCount() {
        return hydeCacheMiss.count();
    }
}
