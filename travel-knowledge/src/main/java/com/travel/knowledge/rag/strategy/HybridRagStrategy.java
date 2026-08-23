package com.travel.knowledge.rag.strategy;

import com.travel.knowledge.rag.rerank.Reranker;
import com.travel.knowledge.rag.rerank.RerankProperties;
import com.travel.knowledge.rag.support.RRFusion;
import com.travel.knowledge.rag.support.RagRoutingMetrics;
import com.travel.knowledge.rag.model.QueryIntent;
import com.travel.knowledge.rag.support.RagFilterBuilder;
import com.travel.knowledge.rag.model.SearchResult;
import com.travel.knowledge.store.EsDocumentStore;
import com.travel.knowledge.store.MilvusVectorStore;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.search.SearchHit;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
    /** M4-6：Rerank SPI（默认 noop 直通）+ 候选池配置 + 指标 */
    private final Reranker reranker;
    private final RerankProperties rerankProperties;
    private final RagRoutingMetrics routingMetrics;

    @Autowired
    public HybridRagStrategy(EsDocumentStore esStore,
                              EmbeddingModel embeddingModel,
                              MilvusVectorStore milvusStore,
                              Reranker reranker,
                              RerankProperties rerankProperties,
                              RagRoutingMetrics routingMetrics) {
        this.esStore = esStore;
        this.embeddingModel = embeddingModel;
        this.milvusStore = milvusStore;
        this.reranker = reranker;
        this.rerankProperties = rerankProperties;
        this.routingMetrics = routingMetrics;
    }

    @Override
    protected List<SearchResult> doRetrieve(QueryIntent intent, int topK) throws Exception {
        // M4-6：透明放大——直通 reranker（noop）时保持原内部检索量 topK（结果与原逻辑完全一致，
        // 回归零风险）；真实 reranker 生效时放大到 max(topK, candidate-pool)，最终由 rerank 截回 topK，
        // 上游 Self/Corrective 装饰器与调用方零改动即获益。
        int pool = reranker.passthrough() ? topK : Math.max(topK, rerankProperties.getCandidatePool());
        List<RRFusion.ScoredItem> bm25Results = bm25Search(intent, pool);
        List<RRFusion.ScoredItem> knnResults = knnSearch(intent, pool);
        List<RRFusion.FusionResult> fused = RRFusion.fuse(bm25Results, knnResults, pool);
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
        List<SearchResult> reranked = reranker.rerank(intent.rawQuery(), merged, topK);
        routingMetrics.recordRerank(System.currentTimeMillis() - rerankStart);
        return reranked;
    }

    /**
     * BM25 文本检索（Elasticsearch）
     */
    private List<RRFusion.ScoredItem> bm25Search(QueryIntent intent, int topK) {
        try {
            List<RRFusion.ScoredItem> results = new ArrayList<>();
            // M3-3：统一经 EsDocumentStore 检索
            for (SearchHit hit : esStore.search(ES_INDEX,
                    RagFilterBuilder.esQuery(intent, intent.rawQuery()), topK)) {
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
            return Collections.emptyList();
        }
    }

    /**
     * KNN 向量检索（Milvus）— 适配 Milvus Java SDK 2.3.4 API
     */
    private List<RRFusion.ScoredItem> knnSearch(QueryIntent intent, int topK) {
        try {
            String expr = RagFilterBuilder.milvusExpr(intent);
            var embeddingResponse = embeddingModel.embedForResponse(List.of(intent.rawQuery()));
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
