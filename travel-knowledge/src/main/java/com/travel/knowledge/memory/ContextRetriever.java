package com.travel.knowledge.memory;

import com.travel.knowledge.rag.support.RRFusion;
import com.travel.knowledge.store.MilvusIndexProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.SearchResults;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.reindex.BulkByScrollResponse;
import org.elasticsearch.index.reindex.DeleteByQueryRequest;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话知识检索域协作件（MM-4.1，自 SessionContextService 原样迁出）。
 *
 * <p>承载读取面：Hybrid 检索（ES BM25 + Milvus KNN → RRF 融合 → F83 相关性+类型
 * 小加分排序）、按 seq 前缀取回（M4-5b 二次取父）、按 seq 前缀删除（M8-9 版本覆盖）。
 * 方法体与迁出前逐字一致；写入域（write/幂等/初始化）留在 SessionContextService。</p>
 */
@Slf4j
@Component
public class ContextRetriever {

    private static final String MILVUS_COLLECTION = "session_context";
    private static final String ES_INDEX = "session_context";

    private final MilvusServiceClient milvusClient;
    private final RestHighLevelClient esClient;
    private final EmbeddingModel embeddingModel;
    private final MilvusIndexProperties milvusIndexProperties;

    public ContextRetriever(MilvusServiceClient milvusClient,
                            RestHighLevelClient esClient,
                            EmbeddingModel embeddingModel,
                            MilvusIndexProperties milvusIndexProperties) {
        this.milvusClient = milvusClient;
        this.esClient = esClient;
        this.embeddingModel = embeddingModel;
        this.milvusIndexProperties = milvusIndexProperties;
    }

    /**
     * 检索会话知识：ES BM25 + Milvus KNN → RRF 融合 → 类型优先级/seq 排序。
     *
     * @return 切片列表（Map：chunkId/sessionId/type/seq/content/role/sourceNode/createdAt）
     */
    public List<Map<String, Object>> search(String sessionId, String query, int topK) {
        if (sessionId == null || sessionId.isBlank() || query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        int k = Math.max(1, Math.min(topK, 20));
        try {
            ensureCollection();
            List<Map<String, Object>> bm25 = esSearch(sessionId, query, k * 2);
            List<Map<String, Object>> knn = milvusSearch(sessionId, query, k * 2);
            // F80：显式转为可变列表后再排序（fuse 契约不保证可变）
            List<Map<String, Object>> fused = new ArrayList<>(fuse(bm25, knn, k));
            fused.sort((a, b) -> {
                // F83：不再"类型硬优先级"（会把行程切片挤出 topK）；
                // 改为 相关性得分 + 类型小加分（constraint/feedback 仅略优先），
                // 保证"景点/行程"查询能召回到 itinerary_day；同分按 seq 排序。
                int cmp = Double.compare(
                        scoreOf(b) + typeBonus(typeOf(b)),
                        scoreOf(a) + typeBonus(typeOf(a)));
                if (cmp != 0) {
                    return cmp;
                }
                return String.valueOf(a.get("seq")).compareTo(String.valueOf(b.get("seq")));
            });
            return fused;
        } catch (Exception e) {
            log.warn("[SessionContext] 检索失败，降级空结果: sessionId={}, error={}, type={}",
                    sessionId, e.getMessage(), e.getClass().getSimpleName(), e);
            return Collections.emptyList();
        }
    }

    /**
     * M4-5b：按 seq 前缀取回会话切片（二次取父）。
     *
     * <p>ES 按 sessionId(term，与 {@link #search} 同口径隔离) + seq 前缀（seq 为 keyword，
     * {@code prefixQuery} 直接命中原始值）过滤，按 seq 升序取 limit 条；content 完整返回
     * （与写入一致的原文，不做 300 字截断），供 planning 侧拼出 itinerary_day 完整父视图。
     * 任何失败降级空列表 + WARN（调用方保留原命中，回归零风险）。</p>
     *
     * @param sessionId 会话 id（隔离键）
     * @param seqPrefix seq 前缀（如 {@code "itin:123:"}）
     * @param limit     返回条数上限（1~100 夹逼，默认调用方传 30）
     * @return 按 seq 升序的切片列表（结构同 {@link #search} 的返回，不含 score）
     */
    public List<Map<String, Object>> findBySeqPrefix(String sessionId, String seqPrefix, int limit) {
        if (sessionId == null || sessionId.isBlank() || seqPrefix == null || seqPrefix.isBlank()) {
            return Collections.emptyList();
        }
        try {
            SearchSourceBuilder source = new SearchSourceBuilder()
                    .query(QueryBuilders.boolQuery()
                            .filter(QueryBuilders.termQuery("sessionId", sessionId))
                            .filter(QueryBuilders.prefixQuery("seq", seqPrefix)))
                    .size(Math.max(1, Math.min(limit, 100)))
                    .sort("seq", SortOrder.ASC);
            SearchRequest request = new SearchRequest(ES_INDEX).source(source);
            SearchResponse response = esClient.search(request, RequestOptions.DEFAULT);
            List<Map<String, Object>> hits = new ArrayList<>();
            if (response == null || response.getHits() == null) {
                return hits;
            }
            for (var hit : response.getHits().getHits()) {
                if (hit == null) {
                    continue;
                }
                Map<String, Object> src = hit.getSourceAsMap();
                if (src != null) {
                    hits.add(src);
                }
            }
            return hits;
        } catch (Exception e) {
            log.warn("[SessionContext] 按前缀取回失败，降级空列表: sessionId={}, seqPrefix={}, error={}, type={}",
                    sessionId, seqPrefix, e.getMessage(), e.getClass().getSimpleName(), e);
            return Collections.emptyList();
        }
    }

    /**
     * M8-9：按 seq 前缀删除会话切片（REFINE/重生成时覆盖旧版本，避免新旧版本混叠）。
     *
     * <p>ES delete_by_query（sessionId term + seq prefix）+ Milvus delete（like 表达式），
     * 任一侧失败仅 WARN 不阻断（残留旧切片最多影响观测，不影响主流程）。</p>
     *
     * @return ES 侧删除数（Milvus 删除数不返回）
     */
    public int deleteBySeqPrefix(String sessionId, String seqPrefix) {
        if (sessionId == null || sessionId.isBlank()
                || seqPrefix == null || seqPrefix.isBlank()) {
            return 0;
        }
        int deleted = 0;
        try {
            DeleteByQueryRequest request = new DeleteByQueryRequest(ES_INDEX);
            request.setQuery(QueryBuilders.boolQuery()
                    .filter(QueryBuilders.termQuery("sessionId", sessionId))
                    .filter(QueryBuilders.prefixQuery("seq", seqPrefix)));
            request.setRefresh(true);
            BulkByScrollResponse response = esClient.deleteByQuery(request, RequestOptions.DEFAULT);
            deleted = response == null ? 0 : (int) response.getDeleted();
            log.info("[SessionContext] 按前缀删除(ES): sessionId={}, seqPrefix={}, deleted={}",
                    sessionId, seqPrefix, deleted);
        } catch (Exception e) {
            log.warn("[SessionContext] 按前缀删除(ES)失败: sessionId={}, seqPrefix={}, error={}",
                    sessionId, seqPrefix, e.getMessage());
        }
        try {
            milvusClient.delete(DeleteParam.newBuilder()
                    .withCollectionName(MILVUS_COLLECTION)
                    .withExpr(com.travel.knowledge.rag.support.MilvusExprBuilder
                            .sessionSeqPrefixExpr(sessionId, seqPrefix))
                    .build());
        } catch (Exception e) {
            log.warn("[SessionContext] 按前缀删除(Milvus)失败: sessionId={}, seqPrefix={}, error={}",
                    sessionId, seqPrefix, e.getMessage());
        }
        return deleted;
    }

    // ==================== 内部实现 ====================

    private List<Map<String, Object>> esSearch(String sessionId, String query, int size) throws Exception {
        try {
            SearchSourceBuilder source = new SearchSourceBuilder()
                    .query(QueryBuilders.boolQuery()
                            .must(QueryBuilders.multiMatchQuery(query, "content"))
                            .filter(QueryBuilders.termQuery("sessionId", sessionId)))
                    .size(size);
            SearchRequest request = new SearchRequest(ES_INDEX).source(source);
            SearchResponse response = esClient.search(request, RequestOptions.DEFAULT);
            List<Map<String, Object>> hits = new ArrayList<>();
            if (response == null || response.getHits() == null) {
                return hits;
            }
            for (var hit : response.getHits().getHits()) {
                if (hit == null) {
                    continue;
                }
                Map<String, Object> src = hit.getSourceAsMap();
                if (src != null) {
                    hits.add(src);
                }
            }
            return hits;
        } catch (Exception e) {
            log.warn("[SessionContext] ES 检索失败: error={}, type={}",
                    e.getMessage(), e.getClass().getSimpleName(), e);
            return Collections.emptyList();
        }
    }

    private List<Map<String, Object>> milvusSearch(String sessionId, String query, int size) {
        try {
            float[] queryVector = embed(query);
            // M3-3：统一装箱
            List<Float> queryVectorList = com.travel.knowledge.store.MilvusVectorStore.box(queryVector);
            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(MILVUS_COLLECTION)
                    .withVectorFieldName("vector")
                    .withVectors(List.of(queryVectorList))
                    .withTopK(size)
                    .withMetricType(MetricType.L2)
                    .withExpr(com.travel.knowledge.rag.support.MilvusExprBuilder.eq("sessionId", sessionId))
                    .withOutFields(List.of("sessionId", "type", "seq", "content", "role", "sourceNode", "createdAt"))
                    .build();
            R<SearchResults> response = milvusClient.search(searchParam);
            if (response.getStatus() != R.Status.Success.getCode()) {
                return Collections.emptyList();
            }
            if (response.getData() == null || response.getData().getResults() == null) {
                return Collections.emptyList();
            }
            SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
            List<SearchResultsWrapper.IDScore> scoreList;
            try {
                scoreList = wrapper.getIDScore(0);
            } catch (Exception e) {
                log.warn("[SessionContext] Milvus 空结果解析，按空处理: {}", e.getMessage());
                return Collections.emptyList();
            }
            if (scoreList == null || scoreList.isEmpty()) {
                return Collections.emptyList();
            }
            List<Map<String, Object>> hits = new ArrayList<>();
            for (SearchResultsWrapper.IDScore score : scoreList) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", score.getStrID());
                m.put("sessionId", safe(score, "sessionId"));
                m.put("type", safe(score, "type"));
                m.put("seq", safe(score, "seq"));
                m.put("content", safe(score, "content"));
                m.put("role", safe(score, "role"));
                m.put("sourceNode", safe(score, "sourceNode"));
                m.put("createdAt", safe(score, "createdAt"));
                hits.add(m);
            }
            return hits;
        } catch (Exception e) {
            log.warn("[SessionContext] Milvus 检索失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private String safe(SearchResultsWrapper.IDScore score, String field) {
        try {
            Object v = score.get(field);
            return v == null ? "" : v.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * RRF 融合（k=60，rank 从 1 起）+ 300 字截断。
     *
     * <p>M4-1b：算法收敛到 {@link RRFusion#fuseGeneric}（消除与 rag/support 的双实现）；
     * 本方法仅保留表现层职责（content 截断、score 回填 Map）。</p>
     */
    private List<Map<String, Object>> fuse(List<Map<String, Object>> bm25,
                                           List<Map<String, Object>> knn, int topK) {
        List<RRFusion.RankedItem<Map<String, Object>>> ranked = RRFusion.fuseGeneric(
                bm25, knn, topK,
                hit -> String.valueOf(hit.getOrDefault("id", hit.get("chunkId"))));
        List<Map<String, Object>> out = new ArrayList<>(ranked.size());
        for (RRFusion.RankedItem<Map<String, Object>> r : ranked) {
            Map<String, Object> hit = r.item();
            if (hit == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>(hit);
            String content = String.valueOf(m.get("content"));
            if (content.length() > 300) {
                m.put("content", content.substring(0, 300) + "…");
            }
            m.put("score", r.score());
            out.add(m);
        }
        return out;
    }

    /** F83：相关性之上的类型小加分（保持 constraint/feedback 略优先，但不再硬排） */
    private static double typeBonus(String type) {
        return switch (type == null ? "" : type) {
            case "constraint" -> 0.02;
            case "feedback" -> 0.01;
            default -> 0.0;
        };
    }

    private static double scoreOf(Map<String, Object> m) {
        Object v = m.get("score");
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static String typeOf(Map<String, Object> m) {
        return String.valueOf(m.getOrDefault("type", ""));
    }

    /** 查询向量化（与 SessionContextService#embed 同源口径；检索域自用）。 */
    private float[] embed(String text) {
        var response = embeddingModel.embedForResponse(List.of(text));
        return response.getResults().get(0).getOutput();
    }

    // ==================== 幂等初始化 ====================

    /**
     * Milvus collection 幂等自建（检索自愈 + 写入前置共用；自 SessionContextService 原样迁出）。
     */
    void ensureCollection() {
        try {
            boolean exists = Boolean.TRUE.equals(milvusClient.hasCollection(
                    io.milvus.param.collection.HasCollectionParam.newBuilder().withCollectionName(MILVUS_COLLECTION).build()).getData());
            if (exists) {
                // F-6（P2-9）：输出物理索引实际值（describeIndex DESC 优先，失败降级标注 yml 值口径）
                String physical;
                try {
                    io.milvus.grpc.DescribeIndexResponse descResp = milvusClient.describeIndex(
                            io.milvus.param.index.DescribeIndexParam.newBuilder()
                                    .withCollectionName(MILVUS_COLLECTION)
                                    .build()).getData();
                    io.milvus.response.DescIndexResponseWrapper.IndexDesc idx =
                            new io.milvus.response.DescIndexResponseWrapper(descResp)
                                    .getIndexDescByFieldName("vector");
                    physical = idx == null ? "NO_INDEX(vector)"
                            : idx.getIndexType() + "/" + idx.getExtraParam() + "/" + idx.getMetricType();
                } catch (Exception descEx) {
                    physical = "DESC_FAIL(" + descEx.getMessage() + ")";
                }
                log.info("[MilvusIndex] collection={} physical={} ymlConfig={}/{} (existing)",
                        MILVUS_COLLECTION, physical,
                        milvusIndexProperties.getIndexType(),
                        milvusIndexProperties.getMetric());
                return;
            }
            int dim = embed("init").length;
            java.util.List<io.milvus.param.collection.FieldType> fields = new ArrayList<>();
            fields.add(io.milvus.param.collection.FieldType.newBuilder().withName("id").withDataType(io.milvus.grpc.DataType.VarChar)
                    .withMaxLength(256).withPrimaryKey(true).build());
            fields.add(io.milvus.param.collection.FieldType.newBuilder().withName("vector").withDataType(io.milvus.grpc.DataType.FloatVector)
                    .withDimension(dim).build());
            fields.add(io.milvus.param.collection.FieldType.newBuilder().withName("sessionId").withDataType(io.milvus.grpc.DataType.VarChar)
                    .withMaxLength(64).build());
            fields.add(io.milvus.param.collection.FieldType.newBuilder().withName("type").withDataType(io.milvus.grpc.DataType.VarChar)
                    .withMaxLength(32).build());
            fields.add(io.milvus.param.collection.FieldType.newBuilder().withName("seq").withDataType(io.milvus.grpc.DataType.VarChar)
                    .withMaxLength(128).build());
            fields.add(io.milvus.param.collection.FieldType.newBuilder().withName("content").withDataType(io.milvus.grpc.DataType.VarChar)
                    .withMaxLength(2000).build());
            fields.add(io.milvus.param.collection.FieldType.newBuilder().withName("role").withDataType(io.milvus.grpc.DataType.VarChar)
                    .withMaxLength(16).build());
            fields.add(io.milvus.param.collection.FieldType.newBuilder().withName("sourceNode").withDataType(io.milvus.grpc.DataType.VarChar)
                    .withMaxLength(64).build());
            fields.add(io.milvus.param.collection.FieldType.newBuilder().withName("createdAt").withDataType(io.milvus.grpc.DataType.VarChar)
                    .withMaxLength(64).build());
            milvusClient.createCollection(io.milvus.param.collection.CreateCollectionParam.newBuilder()
                    .withCollectionName(MILVUS_COLLECTION)
                    .withFieldTypes(fields)
                    .build());
            // MR-D3→MR2-1：索引参数显式化+按类型分派 extra 参数（HNSW 用 M/efC，IVF 用 nlist）
            String extraParam;
            if ("HNSW".equalsIgnoreCase(milvusIndexProperties.getIndexType())) {
                extraParam = "{\"M\":" + milvusIndexProperties.getM()
                        + ",\"efConstruction\":" + milvusIndexProperties.getEfConstruction() + "}";
            } else {
                extraParam = "{\"nlist\":" + milvusIndexProperties.getNlist() + "}";
            }
            milvusClient.createIndex(io.milvus.param.index.CreateIndexParam.newBuilder()
                    .withCollectionName(MILVUS_COLLECTION)
                    .withFieldName("vector")
                    .withIndexType(io.milvus.param.IndexType.valueOf(milvusIndexProperties.getIndexType()))
                    .withMetricType(MetricType.valueOf(milvusIndexProperties.getMetric()))
                    .withExtraParam(extraParam)
                    .build());
            milvusClient.loadCollection(io.milvus.param.collection.LoadCollectionParam.newBuilder()
                    .withCollectionName(MILVUS_COLLECTION)
                    .build());
            log.info("[SessionContext] Milvus collection 已自动创建: {} (dim={})", MILVUS_COLLECTION, dim);
            log.info("[MilvusIndex] collection={} indexType={} extraParam={} metric={}",
                    MILVUS_COLLECTION, milvusIndexProperties.getIndexType(), extraParam,
                    milvusIndexProperties.getMetric());
        } catch (Exception e) {
            log.warn("[SessionContext] Milvus collection 初始化失败（写入将降级）: {}", e.getMessage());
        }
    }
}
