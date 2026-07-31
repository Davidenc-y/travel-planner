package com.travel.knowledge.rag;

import com.travel.knowledge.memory.RRFusion;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Hybrid RAG 策略
 *
 * <p>BM25（ES）+ KNN（Milvus）+ RRF 融合，是默认的 RAG 策略。</p>
 *
 * <p>工作流程：</p>
 * <ol>
 *   <li>BM25 文本检索（ES）→ 得分 A</li>
 *   <li>KNN 向量检索（Milvus）→ 得分 B</li>
 *   <li>RRF 融合：score = 1/(k+rank₁) + 1/(k+rank₂)</li>
 *   <li>返回 Top-K 融合结果</li>
 * </ol>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Component("hybridRag")
public class HybridRagStrategy implements RagStrategy {

    private static final String ES_INDEX = "attraction_index";
    private static final String MILVUS_COLLECTION = "attraction_vectors";

    private final RestHighLevelClient esClient;
    private final EmbeddingModel embeddingModel;
    private final io.milvus.client.MilvusServiceClient milvusClient;

    @Autowired
    public HybridRagStrategy(RestHighLevelClient esClient,
                              EmbeddingModel embeddingModel,
                              io.milvus.client.MilvusServiceClient milvusClient) {
        this.esClient = esClient;
        this.embeddingModel = embeddingModel;
        this.milvusClient = milvusClient;
    }

    @Override
    public List<SearchResult> retrieve(String query, int topK) {
        log.info("[HybridRAG] query={}, topK={}", query, topK);
        long start = System.currentTimeMillis();

        // 1. BM25 检索 (ES)
        List<RRFusion.ScoredItem> bm25Results = bm25Search(query, topK);

        // 2. KNN 向量检索 (Milvus)
        List<RRFusion.ScoredItem> knnResults = knnSearch(query, topK);

        // 3. RRF 融合
        List<RRFusion.FusionResult> fused = RRFusion.fuse(bm25Results, knnResults, topK);

        long cost = System.currentTimeMillis() - start;
        log.info("[HybridRAG] 检索完成, 耗时={}ms, BM25={}条, KNN={}条, 融合={}条",
                cost, bm25Results.size(), knnResults.size(), fused.size());

        return fused.stream()
                .map(f -> SearchResult.builder()
                        .docId(f.docId())
                        .title(f.title())
                        .snippet(f.snippet())
                        .score(f.fusedScore())
                        .keywords(f.keywords())
                        .sourceDate(f.sourceDate())
                        .source("hybrid")
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * BM25 文本检索（Elasticsearch）
     */
    private List<RRFusion.ScoredItem> bm25Search(String query, int topK) {
        try {
            var searchRequest = new org.elasticsearch.action.search.SearchRequest(ES_INDEX);
            var sourceBuilder = new SearchSourceBuilder();
            sourceBuilder.query(QueryBuilders.multiMatchQuery(query, "name", "description"));
            sourceBuilder.size(topK);
            searchRequest.source(sourceBuilder);

            var response = esClient.search(searchRequest, org.elasticsearch.client.RequestOptions.DEFAULT);
            List<RRFusion.ScoredItem> results = new ArrayList<>();

            for (SearchHit hit : response.getHits().getHits()) {
                var sourceMap = hit.getSourceAsMap();
                results.add(new RRFusion.ScoredItem(
                        hit.getId(),
                        (String) sourceMap.get("name"),
                        (String) sourceMap.get("description"),
                        hit.getScore(),
                        parseTags(sourceMap.get("tags")),
                        (String) sourceMap.getOrDefault("createdAt", "")
                ));
            }
            return results;
        } catch (Exception e) {
            log.error("[HybridRAG] BM25 检索失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * KNN 向量检索（Milvus）
     */
    private List<RRFusion.ScoredItem> knnSearch(String query, int topK) {
        try {
            // 1. 生成查询向量
            var embeddingResponse = embeddingModel.embedForResponse(List.of(query));
            float[] queryVector = embeddingResponse.getResults().get(0).getOutput();

            // 2. Milvus 向量检索
            var searchParam = io.milvus.param.dml.SearchParam.newBuilder()
                    .withCollectionName(MILVUS_COLLECTION)
                    .withVectorFields("vector")
                    .withVectors(List.of(queryVector))
                    .withVectorFieldName("vector")
                    .withTopK(topK)
                    .withMetricType(io.milvus.param.MetricType.L2)
                    .withOutFields(List.of("name", "city", "type", "tags", "rating", "ticketPrice", "createdAt"))
                    .build();

            var searchResults = milvusClient.search(searchParam);
            List<RRFusion.ScoredItem> results = new ArrayList<>();

            if (searchResults.getData() != null) {
                for (var result : searchResults.getData()) {
                    var entity = result.getEntity("name");
                    String name = entity != null ? entity.toString() : "";
                    float score = result.getScore();
                    // Milvus L2 距离越小越相似，转换为相似度（1/(1+distance)）
                    double similarity = 1.0 / (1.0 + score);

                    results.add(new RRFusion.ScoredItem(
                            String.valueOf(result.getID()),
                            name,
                            "",  // snippet 从 ES 补充
                            similarity,
                            Collections.emptyList(),
                            ""
                    ));
                }
            }
            return results;
        } catch (Exception e) {
            log.error("[HybridRAG] KNN 检索失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 安全解析 tags 字段
     */
    @SuppressWarnings("unchecked")
    private List<String> parseTags(Object tagsObj) {
        if (tagsObj == null) return Collections.emptyList();
        if (tagsObj instanceof List) return (List<String>) tagsObj;
        if (tagsObj instanceof String s) {
            // JSON 数组字符串，简化处理
            return List.of(s.replaceAll("[\\[\\]\"]", "").split(","));
        }
        return Collections.emptyList();
    }

    @Override
    public String getType() {
        return "hybrid";
    }
}
