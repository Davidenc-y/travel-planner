package com.travel.knowledge.rag;

import com.travel.knowledge.memory.RRFusion;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.dml.SearchParam;
import io.milvus.grpc.SearchResults;
import io.milvus.response.SearchResultsWrapper;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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

        List<RRFusion.ScoredItem> bm25Results = bm25Search(query, topK);
        List<RRFusion.ScoredItem> knnResults = knnSearch(query, topK);
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
            // F39：查询含城市时严格限定 city（term filter），
            // 避免"北京文化景点"混入深圳等异地景点（TC-20 实测）。
            var boolQuery = QueryBuilders.boolQuery();
            boolQuery.must(QueryBuilders.multiMatchQuery(query, "name", "description"));
            String city = RagCityFilter.detect(query);
            if (city != null) {
                boolQuery.filter(QueryBuilders.termQuery("city", city));
            }
            sourceBuilder.query(boolQuery);
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
     * KNN 向量检索（Milvus）— 适配 Milvus Java SDK 2.3.4 API
     */
    private List<RRFusion.ScoredItem> knnSearch(String query, int topK) {
        try {
            // F39：查询含城市时对 Milvus 侧同样限定 city（expr 过滤），双路一致。
            String city = RagCityFilter.detect(query);

            // 1. 生成查询向量
            var embeddingResponse = embeddingModel.embedForResponse(List.of(query));
            float[] queryVector = embeddingResponse.getResults().get(0).getOutput();

            // F35：Milvus SDK 要求 SearchParam 的 float 向量为 List<Float>（同 F31 插入侧装箱）。
            // 直接传 float[] 会在 build() 阶段抛
            // ParamException "Target vector type must be List<Float> or ByteBuffer"，
            // 导致 KNN 路失败、Hybrid 退化为 BM25-only（TC-19 实测根因）。
            List<Float> queryVectorList = new ArrayList<>(queryVector.length);
            for (float v : queryVector) {
                queryVectorList.add(v);
            }

            // 2. 构建 SearchParam（Milvus SDK 2.3.4 API）
            var searchBuilder = SearchParam.newBuilder()
                    .withCollectionName(MILVUS_COLLECTION)
                    .withVectorFieldName("vector")
                    .withVectors(List.of(queryVectorList))
                    .withTopK(topK)
                    .withMetricType(MetricType.L2)
                    .withOutFields(List.of("name", "description", "city", "type", "tags", "rating", "ticketPrice", "createdAt"));
            if (city != null) {
                searchBuilder.withExpr("city == \"" + city + "\"");
            }
            SearchParam searchParam = searchBuilder.build();

            // 3. 执行搜索
            R<SearchResults> response = milvusClient.search(searchParam);

            if (response.getStatus() != R.Status.Success.getCode()) {
                log.error("[HybridRAG] KNN 检索失败: {}", response.getMessage());
                return Collections.emptyList();
            }

            // 4. 解析结果（Milvus SDK 2.3.4 API：IDScore 不是 IDQuery）
            SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
            List<SearchResultsWrapper.IDScore> scoreList = wrapper.getIDScore(0);

            List<RRFusion.ScoredItem> results = new ArrayList<>();
            for (SearchResultsWrapper.IDScore score : scoreList) {
                // F35：attraction_vectors 主键为 VARCHAR(64)（init_milvus.py），必须用 getStrID()。
                // getLongID() 仅适用于 Int64 主键，VARCHAR 下恒为 0，会导致全部 docId 变成 "0"，
                // RRF 融合无法与 BM25（ES _id）匹配（K7）。
                String docId = score.getStrID();
                float distance = score.getScore();
                // Milvus L2 距离越小越相似，转换为相似度
                double similarity = 1.0 / (1.0 + distance);

                // F35：KNN 独有命中直接从 Milvus out fields 回填元数据（K2），
                // 避免仅出现在 KNN 路的文档 title/snippet 为空。
                results.add(new RRFusion.ScoredItem(
                        docId,
                        fieldValue(score, "name"),
                        fieldValue(score, "description"),
                        similarity,
                        parseTags(safeGet(score, "tags")),
                        fieldValue(score, "createdAt")
                ));
            }
            return results;

        } catch (Exception e) {
            log.error("[HybridRAG] KNN 检索失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 安全读取 Milvus 输出字段（缺失/异常时返回空串）
     */
    private String fieldValue(SearchResultsWrapper.IDScore score, String field) {
        Object v = safeGet(score, field);
        return v == null ? "" : v.toString();
    }

    private Object safeGet(SearchResultsWrapper.IDScore score, String field) {
        try {
            return score.get(field);
        } catch (Exception e) {
            return null;
        }
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
