package com.travel.knowledge.rag.strategy;

import com.travel.knowledge.rag.config.TravelConfigKeys;
import com.travel.knowledge.rag.graph.GraphExpander;
import com.travel.knowledge.rag.retrieval.HydeQueryRewriter;
import com.travel.knowledge.rag.retrieval.LlmQueryExpander;
import com.travel.knowledge.rag.rerank.RerankGate;
import com.travel.knowledge.rag.rerank.Reranker;
import com.travel.knowledge.rag.rerank.RerankProperties;
import com.travel.common.trace.SpanCollector;
import com.travel.knowledge.rag.support.HedgeInFlightRegistry;
import com.travel.knowledge.rag.support.QueryTtlCache;
import com.travel.knowledge.rag.support.QueryVectorCache;
import com.travel.knowledge.rag.support.RRFusion;
import com.travel.knowledge.rag.support.RagRoutingMetrics;
import com.travel.knowledge.rag.model.QueryIntent;
import com.travel.knowledge.rag.support.AuthorityTieBreaker;
import com.travel.knowledge.rag.support.RagFilterBuilder;
import com.travel.knowledge.rag.model.SearchResult;
import com.travel.knowledge.store.EsDocumentStore;
import com.travel.knowledge.store.MilvusVectorStore;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.search.SearchHit;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Hybrid RAG 策略
 *
 * <p>BM25（ES）+ KNN（Milvus）+ RRF 融合，是默认的 RAG 策略。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Component("hybridRag")
@SuppressWarnings("deprecation")
public class HybridRagStrategy extends AbstractRagStrategy {

    private static final String ES_INDEX = "attraction_index";
    private static final String MILVUS_COLLECTION = "attraction_vectors";

    /** AJ-2a：并行检索虚拟线程执行器（静态单例，DashScopeReranker:50 先例同款） */
    private static final ExecutorService VIRTUAL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final EsDocumentStore esStore;
    private final EmbeddingModel embeddingModel;
    private final MilvusVectorStore milvusStore;
    private final RagFilterBuilder ragFilterBuilder;
    /** M4-6：Rerank SPI（默认 noop 直通）+ 候选池配置 + 指标 */
    private final Reranker reranker;
    private final RerankProperties rerankProperties;
    private final RagRoutingMetrics routingMetrics;
    /** MR-B1：rerank 低置信阈值门控（基于本类已注入的 rerankProperties 构造，不增构造签名） */
    private final RerankGate rerankGate;
    /** MR-D1：HyDE 假设答案改写器（travel.rag.query.hyde.enabled 默认 false=原 query） */
    private final HydeQueryRewriter hydeQueryRewriter;
    /** RK-9：权威裁决 tie-break（tie-break-enabled 默认 false=原样返回，rerank 后 RerankGate 前） */
    private final AuthorityTieBreaker authorityTieBreaker;
    /** AO-1a：查询向量缓存（optional 注入=MR-D2 先例：构造签名零变更；bean 缺省=null=无缓存直通现状 E-33） */
    private QueryVectorCache queryVectorCache;
    /** MR-D2：LLM 多 Query 扩展器（llm-expand.enabled=true 才有 bean；未注入=现状单路） */
    private LlmQueryExpander llmQueryExpander;
    /** S-B2/B-2a（L1c）：对冲单飞注册表（optional 注入，bean 缺省=关闭态零变更，构造签名零变更——MR-D2 先例） */
    private HedgeInFlightRegistry hedgeRegistry;
    /** S-B2：hedge-enabled 默认 false=关闭态逐字节等价（E-33） */
    @Value("${" + TravelConfigKeys.RAG_HEDGE_ENABLED + ":false}")
    private boolean hedgeEnabled;

    /** AJ-2a：双路并行开关（默认 false=串行现状字节等价 E-33；包内可见=单测直设面） */
    @Value("${travel.rag.parallel-retrieval.enabled:false}")
    boolean parallelRetrieval;

    /** AJ-2a：单路独立超时 ms（E-58 各路独立 orTimeout；默认 4s=不超现状串行上界；包内可见=单测直设面） */
    @Value("${travel.rag.parallel-retrieval.parallel-timeout-ms:4000}")
    long parallelTimeoutMs;

    /** AN-2：串行检索同步超时 ms（默认 0=不限现状字节等价 E-33；包内可见=单测直设面） */
    @Value("${travel.rag.retrieval-sync-timeout-ms:0}")
    long retrievalSyncTimeoutMs;

    /** AO-1b：embedding 并发闸容量（默认 0=不限不建不闸 QU 同款 E-33；包内可见=单测直设面） */
    @Value("${travel.rag.embedding.max-concurrent:0}")
    int embeddingMaxConcurrent;

    /** AO-1b：embed 调用限时 ms（默认 0=不限现状字节等价 E-33；包内可见=单测直设面） */
    @Value("${travel.rag.embedding.timeout-ms:0}")
    long embeddingTimeoutMs;

    /** AO-2：检索 IO 平台池线程数（默认 0=虚拟线程现状 VIRTUAL_EXECUTOR 字节等价 E-33；>0=平台固定池；包内可见=单测直设面） */
    @Value("${travel.rag.retrieval-io-threads:0}")
    int retrievalIoThreads;

    /** AO-1b：embedding 信号量（懒建随 embeddingMaxConcurrent 配置值；0=不建不闸） */
    private Semaphore embeddingSlots;
    private int embeddingSlotsCap;

    /** AO-1b：信号量懒建访问器（容量随配置值；0=返回 null=不闸，E-33 现状语义；包级=测试同包直取观测面） */
    Semaphore embeddingSlots() {
        int cap = embeddingMaxConcurrent;
        if (cap <= 0) {
            return null;
        }
        synchronized (this) {
            if (embeddingSlots == null || embeddingSlotsCap != cap) {
                embeddingSlots = new Semaphore(cap);
                embeddingSlotsCap = cap;
            }
            return embeddingSlots;
        }
    }

    /** AO-2：检索 IO 执行器懒建访问器（0=虚拟线程现状 VIRTUAL_EXECUTOR；N=平台固定池 newFixedThreadPool(N)，载体=AN-2 包裹点；包级=测试同包直取观测面） */
    private ExecutorService retrievalIoPool;
    private int retrievalIoPoolThreads = -1;

    ExecutorService retrievalIoExecutor() {
        if (retrievalIoThreads <= 0) {
            return VIRTUAL_EXECUTOR;
        }
        synchronized (this) {
            if (retrievalIoPool == null || retrievalIoPoolThreads != retrievalIoThreads) {
                if (retrievalIoPool != null) {
                    retrievalIoPool.shutdown();
                }
                retrievalIoPool = Executors.newFixedThreadPool(retrievalIoThreads);
                retrievalIoPoolThreads = retrievalIoThreads;
            }
            return retrievalIoPool;
        }
    }

    /** MR-D2：optional 注入（bean 缺省=现状单路，构造签名零变更） */
    @Autowired(required = false)
    void setLlmQueryExpander(LlmQueryExpander llmQueryExpander) {
        this.llmQueryExpander = llmQueryExpander;
    }

    /** S-B2：optional 注入对冲协作件（B-1 注册表 bean） */
    @Autowired(required = false)
    void setHedgeRegistry(HedgeInFlightRegistry hedgeRegistry) {
        this.hedgeRegistry = hedgeRegistry;
    }

    /** AO-1a：optional 注入查询向量缓存（生产 bean 常在=缓存默认开；测试同包直设面） */
    @Autowired(required = false)
    void setQueryVectorCache(QueryVectorCache queryVectorCache) {
        this.queryVectorCache = queryVectorCache;
    }

    /** AG-1c：GraphExpander 邻域扩展器（optional 注入=MR-D2 先例：构造签名零变更；bean 缺省=null=挂点直通 fail-open） */
    private GraphExpander graphExpander;

    @Autowired(required = false)
    void setGraphExpander(GraphExpander graphExpander) {
        this.graphExpander = graphExpander;
    }

    /** 包内可见（S-B2a 单测观察用） */
    HedgeInFlightRegistry getHedgeRegistry() {
        return hedgeRegistry;
    }

    /** S-B5b：Span 采集挂点（optional 注入，缺省自给=未绑定时挂点空安全跳过） */
    private SpanCollector spanCollector = new SpanCollector();

    @Autowired(required = false)
    void setSpanCollector(SpanCollector spanCollector) {
        this.spanCollector = spanCollector;
    }

    @Autowired
    public HybridRagStrategy(EsDocumentStore esStore,
                              EmbeddingModel embeddingModel,
                              MilvusVectorStore milvusStore,
                              RagFilterBuilder ragFilterBuilder,
                              Reranker reranker,
                              RerankProperties rerankProperties,
                              RagRoutingMetrics routingMetrics,
                              HydeQueryRewriter hydeQueryRewriter,
                              AuthorityTieBreaker authorityTieBreaker) {
        this.esStore = esStore;
        this.embeddingModel = embeddingModel;
        this.milvusStore = milvusStore;
        this.ragFilterBuilder = ragFilterBuilder;
        this.reranker = reranker;
        this.rerankProperties = rerankProperties;
        this.routingMetrics = routingMetrics;
        this.rerankGate = new RerankGate(rerankProperties);
        this.hydeQueryRewriter = hydeQueryRewriter;
        this.authorityTieBreaker = authorityTieBreaker;
    }

    @Override
    protected List<SearchResult> doRetrieve(QueryIntent intent, int poolSize) throws Exception {
        // S-B2/B-2a（L1c）：对冲单飞——hedge 关闭（默认）逐字节等价直通（E-33）；
        // 开启时同键（hybrid|intent|poolSize）仅一路执行：预启（B-2b dispatcher）与工具/兜底路径
        // 复用同一 future，双倍成本→单倍；预启失败/超时（有界 future，F23）→内联重取兜底。
        if (!hedgeEnabled || hedgeRegistry == null) {
            return doRetrieveInternal(intent, poolSize);
        }
        String hedgeKey = QueryTtlCache.buildKey("hybrid", intent, poolSize);
        CompletableFuture<List<SearchResult>> created = new CompletableFuture<>();
        CompletableFuture<List<SearchResult>> existing = hedgeRegistry.register(hedgeKey, created);
        if (existing != created) {
            try {
                log.info("[HedgeInFlight] 单飞复用预启对冲结果: key={}", hedgeKey);
                return existing.join();
            } catch (CompletionException e) {
                log.warn("[HedgeInFlight] 预启对冲失败，内联重取兜底: key={}", hedgeKey);
                created = new CompletableFuture<>();
                existing = hedgeRegistry.register(hedgeKey, created);
                if (existing != created) {
                    return existing.join();
                }
                return completeInline(created, intent, poolSize);
            }
        }
        return completeInline(created, intent, poolSize);
    }

    private List<SearchResult> completeInline(CompletableFuture<List<SearchResult>> created,
                                              QueryIntent intent, int poolSize) throws Exception {
        try {
            List<SearchResult> results = doRetrieveInternal(intent, poolSize);
            created.complete(results);
            return results;
        } catch (Exception e) {
            created.completeExceptionally(e);
            throw e;
        }
    }

    /**
     * 既有 doRetrieve 管线本体（S-B2a 提取；包内可见便于单测挂桩）。
     * M8-9d：poolSize 由模板 retrievalPoolSize 统一给出（质量/精排放大），
     * 此处只负责召回+融合+池内精排，不再自行截断（截断收口到模板出口）。
     */
    List<SearchResult> doRetrieveInternal(QueryIntent intent, int poolSize) throws Exception {
        // S-B5b：检索五段 span（同线程上下文，未绑定空安全跳过；join 复用路径不经过本方法=零重复记录）
        List<RRFusion.FusionResult> fused;
        if (!parallelRetrieval) {
            // AJ-2a 串行分支：现状代码逐字保留禁重排（P0⑯）
            SpanCollector.Span bm25Span = spanCollector.start("bm25", "retrieval");
            // AN-2：串行调用体限时包裹（只包调用体不改结构=P0⑳；span start/end 主线程词面零触碰）
            List<RRFusion.ScoredItem> bm25Results = syncBoundedGet("bm25", () -> bm25Search(intent, poolSize));
            if (bm25Span != null) {
                spanCollector.end(bm25Span, bm25Results.isEmpty() ? "empty" : "ok", Map.of("count", bm25Results.size()));
            }
            // MR-D1：HyDE——假设答案文本仅参与向量路（KNN），原 query 保留参与 BM25（文章 §3.2
            // 双路语义）；门控关/fail-open 一律原 query（E-33）
            // MR-D2：llm-expand.enabled=true（bean 存在）时走多 Query 路：变体各自向量检索 +
            // RRFusion 级联合并（复用 G11）；未注入=现状单路（E-33）
            SpanCollector.Span knnSpan = spanCollector.start("knn", "retrieval");
            // AN-2：knn 路调用体限时包裹（三元词面在 lambda 内逐字保留；expand 子 span 工作线程态
            // null 安全跳过=AJ-2a N2 同族已知观测缺口，仅 timeout 开启时）
            List<RRFusion.ScoredItem> knnResults = syncBoundedGet("knn", () -> llmQueryExpander != null
                    ? knnMultiQuery(intent, poolSize)
                    : knnSearch(intent, hydeQueryRewriter.vectorQueryText(intent), poolSize));
            if (knnSpan != null) {
                spanCollector.end(knnSpan, knnResults.isEmpty() ? "empty" : "ok",
                        Map.of("count", knnResults.size(), "expanded", llmQueryExpander != null));
            }
            fused = RRFusion.fuse(bm25Results, knnResults, poolSize);
        } else {
            // AJ-2a 双路并行（E-58 失败面收敛）。span 主线程模式（P0⑮）：两路 span 的
            // start/end 均在主线程、闭包仅捕获引用，工作线程零 trace 调用（knnMultiQuery
            // 内部 expand 子 span 经工作线程 null 安全跳过=并行态已知观测缺口，记录无损坏）。
            SpanCollector.Span bm25Span = spanCollector.start("bm25", "retrieval");
            SpanCollector.Span knnSpan = spanCollector.start("knn", "retrieval");
            AtomicReference<Throwable> bm25Failure = new AtomicReference<>();
            AtomicReference<Throwable> knnFailure = new AtomicReference<>();
            CompletableFuture<List<RRFusion.ScoredItem>> bm25F = CompletableFuture
                    .supplyAsync(() -> bm25Search(intent, poolSize), VIRTUAL_EXECUTOR)
                    .orTimeout(parallelTimeoutMs, TimeUnit.MILLISECONDS)
                    .exceptionally(e -> {
                        bm25Failure.set(e);
                        log.warn("[ParallelRetrieval] bm25 路失败按空融合: {}", e.getMessage());
                        return List.of();
                    });
            CompletableFuture<List<RRFusion.ScoredItem>> knnF = CompletableFuture
                    .supplyAsync(() -> llmQueryExpander != null
                            ? knnMultiQuery(intent, poolSize)
                            : knnSearch(intent, hydeQueryRewriter.vectorQueryText(intent), poolSize),
                            VIRTUAL_EXECUTOR)
                    .orTimeout(parallelTimeoutMs, TimeUnit.MILLISECONDS)
                    .exceptionally(e -> {
                        knnFailure.set(e);
                        log.warn("[ParallelRetrieval] knn 路失败按空融合: {}", e.getMessage());
                        return List.of();
                    });
            CompletableFuture.allOf(bm25F, knnF).join();
            List<RRFusion.ScoredItem> bm25Results = bm25F.join();
            List<RRFusion.ScoredItem> knnResults = knnF.join();
            if (bm25Span != null) {
                spanCollector.end(bm25Span, bm25Results.isEmpty() ? "empty" : "ok", Map.of("count", bm25Results.size()));
            }
            if (knnSpan != null) {
                spanCollector.end(knnSpan, knnResults.isEmpty() ? "empty" : "ok",
                        Map.of("count", knnResults.size(), "expanded", llmQueryExpander != null));
            }
            if (bm25Failure.get() != null && knnFailure.get() != null) {
                // E-58：两路全败→抛原首异常（bm25=首路；单路失败已按空融合不抛）
                Throwable first = bm25Failure.get();
                if (first instanceof Exception ex) {
                    throw ex;
                }
                throw new IllegalStateException(first);
            }
            fused = RRFusion.fuse(bm25Results, knnResults, poolSize);
        }
        List<SearchResult> merged = fused.stream()
                .map(f -> SearchResult.builder()
                        .docId(f.docId())
                        .title(f.title())
                        .snippet(f.snippet())
                        .score(f.fusedScore())
                        .keywords(f.keywords())
                        .sourceDate(f.sourceDate())
                        .imageUrl(f.imageUrl())
                        .source("hybrid")
                        .build())
                .collect(Collectors.toList());
        long rerankStart = System.currentTimeMillis();
        SpanCollector.Span rerankSpan = spanCollector.start("rerank", "rerank");
        // AG-1c：GraphExpander 邻域扩展——graph-expand.enabled 默认 false=expand() 首行直通返回原列表
        //（字节等价 E-33）；开启时扩展项入池被既有 rerank/gate 消费（pool 放大语义）；bean 缺省=null 直通（fail-open 双保险）
        List<SearchResult> expanded = graphExpander != null
                ? graphExpander.expand(merged)
                : merged;
        List<SearchResult> reranked = reranker.rerank(intent.rawQuery(), expanded);
        routingMetrics.recordRerank(System.currentTimeMillis() - rerankStart);
        if (rerankSpan != null) {
            spanCollector.end(rerankSpan, "ok", Map.of("candidates", merged.size()));
        }
        // RK-9：权威裁决 tie-break（默认关=原样返回字节等价；rerank 后、RerankGate 前）
        List<SearchResult> tieBroken = authorityTieBreaker.apply(reranked);
        // MR-B1：阈值门控（默认 0.0=关闭，apply 原样返回零路径变更）
        SpanCollector.Span gateSpan = spanCollector.start("gate", "gate");
        // S 审计复合门控：QU 无城市且无类型=离域信号，走 emptyIntentThreshold
        boolean intentEmpty = intent == null || (intent.city() == null && intent.type() == null);
        List<SearchResult> gated = rerankGate.apply(tieBroken, intentEmpty);
        if (gateSpan != null) {
            spanCollector.end(gateSpan, "ok", Map.of("kept", gated.size()));
        }
        return gated;
    }

    /**
     * AN-2：串行检索调用体限时包裹（retrieval-sync-timeout-ms>0 时虚拟线程 FutureTask 限时 get，
     * 超时=cancel(true)+降级空融合+计数；0=不限现状直调字节等价 E-33）。
     *
     * <p>E-58 合规：仅调用体进工作线程（AJ-2a 闭包捕获同构），调用方 span start/end 保持主线程
     * 词面零触碰；P0⑲ 语义本体=真实打断底层调用，故载体=FutureTask（CompletableFuture.cancel
     * 不中断运行中任务，R461 教训）。sync_retrieval_timeout 与 es_fail 同族（reason 第六值）。</p>
     */
    private List<RRFusion.ScoredItem> syncBoundedGet(String label, Supplier<List<RRFusion.ScoredItem>> call) {
        long timeoutMs = retrievalSyncTimeoutMs;
        if (timeoutMs <= 0) {
            return call.get();
        }
        FutureTask<List<RRFusion.ScoredItem>> future = new FutureTask<>(call::get);
        retrievalIoExecutor().execute(future);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true); // 超时路径必须 cancel(true)（skill 四硬规则=P0⑲）
            log.warn("[HybridRAG] {} 串行检索超时({}ms)，按空融合降级", label, timeoutMs);
            routingMetrics.recordDegraded("sync_retrieval_timeout");
            return Collections.emptyList();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            log.warn("[HybridRAG] {} 串行检索等待被中断，按空融合降级", label);
            routingMetrics.recordDegraded("sync_retrieval_timeout");
            return Collections.emptyList();
        } catch (ExecutionException ee) {
            // bm25/knnSearch 自带 catch→空+recordDegraded 永不抛；载体面兜底按原异常语义外抛（方案 §2.2）
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(cause);
        }
    }

    /**
     * M8-9d：召回池 = max(质量池, 精排池)。noop + 质量关 → topK（现状不变）；
     * dashscope（非直通）→ 至少 candidatePool（保留 M4-6 放大语义）；
     * 质量开 → 两者取最大。
     */
    @Override
    protected int retrievalPoolSize(int topK) {
        int pool = super.retrievalPoolSize(topK);
        return reranker.passthrough() ? pool : Math.max(pool, rerankProperties.getCandidatePool());
    }

    /**
     * BM25 文本检索（Elasticsearch）
     */
    private List<RRFusion.ScoredItem> bm25Search(QueryIntent intent, int topK) {
        try {
            List<RRFusion.ScoredItem> results = new ArrayList<>();
            // M3-3：统一经 EsDocumentStore 检索
            for (SearchHit hit : esStore.search(ES_INDEX,
                    ragFilterBuilder.esQuery(intent, intent.rawQuery()), topK)) {
                var sourceMap = hit.getSourceAsMap();
                results.add(new RRFusion.ScoredItem(
                        hit.getId(),
                        (String) sourceMap.get("name"),
                        (String) sourceMap.get("description"),
                        hit.getScore(),
                        parseTags(sourceMap.get("tags")),
                        (String) sourceMap.getOrDefault("createdAt", ""),
                        (String) sourceMap.getOrDefault("imageUrl", "")
                ));
            }
            return results;
        } catch (Exception e) {
            log.error("[HybridRAG] BM25 检索失败", e);
            routingMetrics.recordDegraded("es_fail");
            return Collections.emptyList();
        }
    }

    /**
     * MR-D2：LLM 多 Query 路——变体各自向量检索，RRFusion 级联合并去重（复用 G11）；
     * 变体数由扩展器决定（fail-open 单变体=退化为单路）。
     */
    private List<RRFusion.ScoredItem> knnMultiQuery(QueryIntent intent, int poolSize) {
        // S-B5b：LLM 多路扩展段 span
        SpanCollector.Span expandSpan = spanCollector.start("expand", "expand");
        List<String> variants = llmQueryExpander.variantsOf(intent);
        if (expandSpan != null) {
            spanCollector.end(expandSpan, variants == null || variants.isEmpty() ? "empty" : "ok",
                    Map.of("variants", variants == null ? 0 : variants.size()));
        }
        if (variants == null || variants.isEmpty()) {
            return knnSearch(intent, hydeQueryRewriter.vectorQueryText(intent), poolSize);
        }
        List<RRFusion.ScoredItem> acc = knnSearch(intent, variants.get(0), poolSize);
        for (int i = 1; i < variants.size(); i++) {
            List<RRFusion.FusionResult> fused = RRFusion.fuse(acc,
                    knnSearch(intent, variants.get(i), poolSize), poolSize);
            acc = fused.stream()
                    .map(f -> new RRFusion.ScoredItem(f.docId(), f.title(), f.snippet(),
                            f.fusedScore(), f.keywords(), f.sourceDate(), f.imageUrl()))
                    .collect(Collectors.toList());
        }
        return acc;
    }

    /**
     * KNN 向量检索（Milvus）— 适配 Milvus Java SDK 2.3.4 API
     */
    private List<RRFusion.ScoredItem> knnSearch(QueryIntent intent, String vectorQueryText, int topK) {        try {
            String expr = ragFilterBuilder.milvusExpr(intent);
            // MR-D1：向量路文本 = HyDE 假设答案（门控开）或原 query（门控关/fail-open）
            // AO-1a（GO-1）两级顺序 cache→闸→embed：命中直接复用缓存向量，不占信号量（P0⑱）
            float[] queryVector = queryVectorCache == null ? null : queryVectorCache.get(vectorQueryText);
            if (queryVector == null) {
                // AO-1b：并发闸 tryAcquire 失败=knn 路弃用降级 bm25-only（空列表=milvus_fail 同构，禁抛出）
                Semaphore slots = embeddingSlots();
                if (slots != null && !slots.tryAcquire()) {
                    routingMetrics.recordDegraded("embedding_overflow");
                    routingMetrics.recordEmbeddingOverflow();
                    log.warn("[HybridRAG] embedding 并发帽({})已满，降级 bm25-only", embeddingMaxConcurrent);
                    return Collections.emptyList();
                }
                try {
                    // AO-1b：embed 限时包裹（FutureTask cancel(true)=P0⑲，AN-1 同款）；0=不限现状直调
                    if (embeddingTimeoutMs > 0) {
                        FutureTask<float[]> future = new FutureTask<>(() -> embedCall(vectorQueryText));
                        VIRTUAL_EXECUTOR.execute(future);
                        try {
                            queryVector = future.get(embeddingTimeoutMs, TimeUnit.MILLISECONDS);
                        } catch (TimeoutException te) {
                            future.cancel(true); // 超时路径必须 cancel(true)（skill 四硬规则=P0⑲）
                            log.warn("[HybridRAG] embed 调用超时({}ms)，降级 bm25-only", embeddingTimeoutMs);
                            routingMetrics.recordDegraded("embedding_timeout");
                            routingMetrics.recordEmbeddingTimeout();
                            return Collections.emptyList();
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            future.cancel(true);
                            log.warn("[HybridRAG] embed 调用等待被中断，降级 bm25-only");
                            routingMetrics.recordDegraded("embedding_timeout");
                            routingMetrics.recordEmbeddingTimeout();
                            return Collections.emptyList();
                        } catch (ExecutionException ee) {
                            // embedCall 异常原样外抛语义（R487 拆分标签面）；载体面兜底与 syncBoundedGet 同构
                            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                            if (cause instanceof RuntimeException re) {
                                throw re;
                            }
                            throw new IllegalStateException(cause);
                        }
                    } else {
                        queryVector = embedCall(vectorQueryText);
                    }
                } finally {
                    if (slots != null) {
                        slots.release();
                    }
                }
                if (queryVector == null) {
                    // AO-1b：embedding 段失败独立标签（修正并入 milvus_fail 的观测盲区）——空列表降级禁抛出
                    routingMetrics.recordDegraded("embedding_fail");
                    log.warn("[HybridRAG] embed 调用失败，降级 bm25-only");
                    return Collections.emptyList();
                }
                if (queryVectorCache != null) {
                    // 仅成功结果可缓存（P0㉑）：降级早退/异常路径到不了 put
                    queryVectorCache.put(vectorQueryText, queryVector);
                }
            }
            // M3-3：统一经 MilvusVectorStore 检索（装箱/解析封装）
            List<MilvusVectorStore.SearchRow> rows = milvusStore.search(
                    MILVUS_COLLECTION, MilvusVectorStore.box(queryVector), expr, topK,
                    List.of("name", "description", "city", "type", "tags",
                            "rating", "ticketPrice", "createdAt", "imageUrl"),
                    io.milvus.param.MetricType.L2);
            List<RRFusion.ScoredItem> results = new ArrayList<>();
            for (MilvusVectorStore.SearchRow row : rows) {
                double similarity = 1.0 / (1.0 + row.score());
                Map<String, Object> f = row.fields();
                results.add(new RRFusion.ScoredItem(
                        row.id(),
                        str(f.get("name")),
                        str(f.get("description")),
                        similarity,
                        parseTags(f.get("tags")),
                        str(f.get("createdAt")),
                        str(f.get("imageUrl"))
                ));
            }
            return results;

        } catch (Exception e) {
            log.error("[HybridRAG] KNN 检索失败", e);
            routingMetrics.recordDegraded("milvus_fail");
            return Collections.emptyList();
        }
    }

    /**
     * AO-1b：embed 调用本体（既有 embed 两行原样迁入）。R487：调用计数+独立 try——
     * 失败=embedding_fail 专属降级面（catch→null=extractByLlm 同款形态），不再并入
     * milvus_fail 观测盲区；null 由调用方转空列表降级。
     */
    private float[] embedCall(String vectorQueryText) {
        routingMetrics.recordEmbeddingCall();
        try {
            var embeddingResponse = embeddingModel.embedForResponse(List.of(vectorQueryText));
            return embeddingResponse.getResults().get(0).getOutput();
        } catch (Exception e) {
            log.warn("[HybridRAG] embed 调用失败: {}", e.getMessage());
            return null;
        }
    }

    private String str(Object v) {
        return v == null ? "" : v.toString();
    }

    /**
     * 安全解析 tags 字段
     */
    @SuppressWarnings("unchecked")
    private List<String> parseTags(Object tagsObj) {
        if (tagsObj == null) return Collections.emptyList();
        // F39：trim 掉 Milvus 侧 JSON 字符串（["文化", "历史"]）按逗号切分产生的
        // 前导空格（如 " 历史"、" 自然"），避免关键词脏数据影响展示。
        if (tagsObj instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
        if (tagsObj instanceof String s) {
            return Arrays.stream(s.replaceAll("[\\[\\]\"]", "").split(","))
                    .map(String::trim)
                    .filter(t -> !t.isEmpty())
                    .toList();
        }
        return Collections.emptyList();
    }

    @Override
    public String getType() {
        return "hybrid";
    }
}
