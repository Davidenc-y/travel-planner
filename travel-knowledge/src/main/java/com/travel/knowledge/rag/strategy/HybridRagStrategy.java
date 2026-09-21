package com.travel.knowledge.rag.strategy;

import com.travel.knowledge.rag.retrieval.HydeQueryRewriter;
import com.travel.knowledge.rag.retrieval.LlmQueryExpander;
import com.travel.knowledge.rag.rerank.RerankGate;
import com.travel.knowledge.rag.rerank.Reranker;
import com.travel.knowledge.rag.rerank.RerankProperties;
import com.travel.common.trace.SpanCollector;
import com.travel.knowledge.rag.support.HedgeInFlightRegistry;
import com.travel.knowledge.rag.support.QueryTtlCache;
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
    /** MR-D2：LLM 多 Query 扩展器（llm-expand.enabled=true 才有 bean；未注入=现状单路） */
    private LlmQueryExpander llmQueryExpander;
    /** S-B2/B-2a（L1c）：对冲单飞注册表（optional 注入，bean 缺省=关闭态零变更，构造签名零变更——MR-D2 先例） */
    private HedgeInFlightRegistry hedgeRegistry;
    /** S-B2：hedge-enabled 默认 false=关闭态逐字节等价（E-33） */
    @Value("${travel.rag.hedge-enabled:false}")
    private boolean hedgeEnabled;

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
        SpanCollector.Span bm25Span = spanCollector.start("bm25", "retrieval");
        List<RRFusion.ScoredItem> bm25Results = bm25Search(intent, poolSize);
        if (bm25Span != null) {
            spanCollector.end(bm25Span, bm25Results.isEmpty() ? "empty" : "ok", Map.of("count", bm25Results.size()));
        }
        // MR-D1：HyDE——假设答案文本仅参与向量路（KNN），原 query 保留参与 BM25（文章 §3.2
        // 双路语义）；门控关/fail-open 一律原 query（E-33）
        // MR-D2：llm-expand.enabled=true（bean 存在）时走多 Query 路：变体各自向量检索 +
        // RRFusion 级联合并（复用 G11）；未注入=现状单路（E-33）
        SpanCollector.Span knnSpan = spanCollector.start("knn", "retrieval");
        List<RRFusion.ScoredItem> knnResults = llmQueryExpander != null
                ? knnMultiQuery(intent, poolSize)
                : knnSearch(intent, hydeQueryRewriter.vectorQueryText(intent), poolSize);
        if (knnSpan != null) {
            spanCollector.end(knnSpan, knnResults.isEmpty() ? "empty" : "ok",
                    Map.of("count", knnResults.size(), "expanded", llmQueryExpander != null));
        }
        List<RRFusion.FusionResult> fused = RRFusion.fuse(bm25Results, knnResults, poolSize);
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
        List<SearchResult> reranked = reranker.rerank(intent.rawQuery(), merged);
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
            var embeddingResponse = embeddingModel.embedForResponse(List.of(vectorQueryText));
            float[] queryVector = embeddingResponse.getResults().get(0).getOutput();
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
