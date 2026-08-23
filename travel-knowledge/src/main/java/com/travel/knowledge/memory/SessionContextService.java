package com.travel.knowledge.memory;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.SearchResults;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.xcontent.XContentType;
import com.travel.knowledge.rag.support.RRFusion;
import com.travel.knowledge.store.MilvusVectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 会话级知识服务（Phase C/F78，C2）。
 *
 * <p>接收 planning 侧提交的结构化切片：规范化 → Embedding → 双写
 * Milvus {@code session_context}（向量，id=chunkId）+ ES {@code session_context}（BM25）。
 * 检索：sessionId 过滤 + Hybrid RRF 融合，按类型优先级（constraint > feedback >
 * itinerary_day）与 seq/时间排序，命中服从 topK。</p>
 *
 * <p>幂等：同一 chunkId 先按主键删除再插入（Milvus insert 不去重，F37 教训）；
 * ES 按 docId 覆盖。collection/index 首次写入时幂等自建。</p>
 */
@Slf4j
@Service
public class SessionContextService {

    private static final String MILVUS_COLLECTION = "session_context";
    private static final String ES_INDEX = "session_context";
    private static final int CONTENT_MAX_CHARS = 2000;

    private final MilvusServiceClient milvusClient;
    private final RestHighLevelClient esClient;
    private final EmbeddingModel embeddingModel;

    public SessionContextService(MilvusServiceClient milvusClient,
                                 RestHighLevelClient esClient,
                                 EmbeddingModel embeddingModel) {
        this.milvusClient = milvusClient;
        this.esClient = esClient;
        this.embeddingModel = embeddingModel;
    }

    // ==================== 写入 ====================

    /**
     * 写入一条会话知识切片（规范化 + 向量化 + Milvus/ES 双写）。
     */
    public void write(SessionContextChunk chunk) {
        if (chunk == null || chunk.sessionId() == null || chunk.sessionId().isBlank()
                || chunk.content() == null || chunk.content().isBlank()) {
            log.warn("[SessionContext] 切片为空，跳过写入");
            return;
        }
        // F79：幂等命中——ES 已存在且 seq/content 一致时直接跳过（避免 ES 版本墓碑/translog
        // 与 Milvus 删插导致的存储增长；文档数不变但 Storage size 增加的根因）
        if (existsInEs(chunk)) {
            log.info("[SessionContext] 幂等命中，跳过重复写入: chunkId={}", chunk.chunkId());
            return;
        }
        ensureCollection();
        ensureIndex();
        String content = chunk.content().trim();
        if (content.length() > CONTENT_MAX_CHARS) {
            content = content.substring(0, CONTENT_MAX_CHARS);
        }
        try {
            float[] vector = embed(content);
            insertToMilvus(chunk, content, vector);
            insertToEs(chunk, content);
            log.info("[SessionContext] 写入成功: chunkId={}, sessionId={}, type={}",
                    chunk.chunkId(), chunk.sessionId(), chunk.type());
        } catch (Exception e) {
            log.warn("[SessionContext] 写入失败: chunkId={}, error={}",
                    chunk.chunkId(), e.getMessage());
        }
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

    // ==================== 内部实现 ====================

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

    private void insertToMilvus(SessionContextChunk chunk, String content, float[] vector) {
        // M3-3：统一装箱（复用 MilvusVectorStore）
        List<Float> vectorList = MilvusVectorStore.box(vector);
        // 幂等：先按主键删除再插入（Milvus insert 不去重，F37 教训）
        try {
            milvusClient.delete(DeleteParam.newBuilder()
                    .withCollectionName(MILVUS_COLLECTION)
                    .withExpr("id in [\"" + chunk.chunkId() + "\"]")
                    .build());
        } catch (Exception ignored) {
            // 删除失败不阻断插入（幂等主要依赖 ES docId 覆盖）
        }
        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("id", Collections.singletonList(chunk.chunkId())));
        fields.add(new InsertParam.Field("vector", Collections.singletonList(vectorList)));
        fields.add(new InsertParam.Field("sessionId", Collections.singletonList(chunk.sessionId())));
        fields.add(new InsertParam.Field("type", Collections.singletonList(chunk.type())));
        fields.add(new InsertParam.Field("seq", Collections.singletonList(chunk.seq())));
        fields.add(new InsertParam.Field("content", Collections.singletonList(content)));
        fields.add(new InsertParam.Field("role", Collections.singletonList(chunk.role())));
        fields.add(new InsertParam.Field("sourceNode", Collections.singletonList(chunk.sourceNode())));
        fields.add(new InsertParam.Field("createdAt", Collections.singletonList(chunk.createdAt())));
        milvusClient.insert(InsertParam.newBuilder()
                .withCollectionName(MILVUS_COLLECTION)
                .withFields(fields)
                .build());
    }

    private void insertToEs(SessionContextChunk chunk, String content) throws Exception {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", chunk.chunkId());
        doc.put("sessionId", chunk.sessionId());
        doc.put("type", chunk.type());
        doc.put("seq", chunk.seq());
        doc.put("content", content);
        doc.put("role", chunk.role());
        doc.put("sourceNode", chunk.sourceNode());
        doc.put("createdAt", chunk.createdAt());
        IndexRequest request = new IndexRequest(ES_INDEX)
                .id(chunk.chunkId())
                .source(doc);
        esClient.index(request, RequestOptions.DEFAULT);
    }

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
            List<Float> queryVectorList = MilvusVectorStore.box(queryVector);
            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(MILVUS_COLLECTION)
                    .withVectorFieldName("vector")
                    .withVectors(List.of(queryVectorList))
                    .withTopK(size)
                    .withMetricType(MetricType.L2)
                    .withExpr("sessionId == \"" + sessionId + "\"")
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

    private float[] embed(String text) {
        var response = embeddingModel.embedForResponse(List.of(text));
        return response.getResults().get(0).getOutput();
    }

    /**
     * F79：ES 中是否已存在相同 chunkId 且 seq/content 一致（幂等判定，避免重复写增长存储）。
     */
    private boolean existsInEs(SessionContextChunk chunk) {
        try {
            GetRequest get = new GetRequest(ES_INDEX, chunk.chunkId());
            GetResponse resp = esClient.get(get, RequestOptions.DEFAULT);
            if (!resp.isExists() || resp.getSourceAsMap() == null) {
                return false;
            }
            Map<String, Object> src = resp.getSourceAsMap();
            return Objects.equals(String.valueOf(src.get("seq")), String.valueOf(chunk.seq()))
                    && Objects.equals(String.valueOf(src.get("content")), String.valueOf(chunk.content()));
        } catch (Exception e) {
            // 查询失败不阻断（走覆盖写路径，保证数据最终一致）
            return false;
        }
    }

    // ==================== 幂等初始化 ====================

    private void ensureCollection() {
        try {
            boolean exists = Boolean.TRUE.equals(milvusClient.hasCollection(
                    HasCollectionParam.newBuilder().withCollectionName(MILVUS_COLLECTION).build()).getData());
            if (exists) {
                return;
            }
            int dim = embed("init").length;
            List<FieldType> fields = new ArrayList<>();
            fields.add(FieldType.newBuilder().withName("id").withDataType(DataType.VarChar)
                    .withMaxLength(256).withPrimaryKey(true).build());
            fields.add(FieldType.newBuilder().withName("vector").withDataType(DataType.FloatVector)
                    .withDimension(dim).build());
            fields.add(FieldType.newBuilder().withName("sessionId").withDataType(DataType.VarChar)
                    .withMaxLength(64).build());
            fields.add(FieldType.newBuilder().withName("type").withDataType(DataType.VarChar)
                    .withMaxLength(32).build());
            fields.add(FieldType.newBuilder().withName("seq").withDataType(DataType.VarChar)
                    .withMaxLength(128).build());
            fields.add(FieldType.newBuilder().withName("content").withDataType(DataType.VarChar)
                    .withMaxLength(CONTENT_MAX_CHARS).build());
            fields.add(FieldType.newBuilder().withName("role").withDataType(DataType.VarChar)
                    .withMaxLength(16).build());
            fields.add(FieldType.newBuilder().withName("sourceNode").withDataType(DataType.VarChar)
                    .withMaxLength(64).build());
            fields.add(FieldType.newBuilder().withName("createdAt").withDataType(DataType.VarChar)
                    .withMaxLength(64).build());
            milvusClient.createCollection(CreateCollectionParam.newBuilder()
                    .withCollectionName(MILVUS_COLLECTION)
                    .withFieldTypes(fields)
                    .build());
            milvusClient.createIndex(CreateIndexParam.newBuilder()
                    .withCollectionName(MILVUS_COLLECTION)
                    .withFieldName("vector")
                    .withIndexType(IndexType.IVF_FLAT)
                    .withMetricType(MetricType.L2)
                    .withExtraParam("{\"nlist\":1024}")
                    .build());
            milvusClient.loadCollection(LoadCollectionParam.newBuilder()
                    .withCollectionName(MILVUS_COLLECTION)
                    .build());
            log.info("[SessionContext] Milvus collection 已自动创建: {} (dim={})", MILVUS_COLLECTION, dim);
        } catch (Exception e) {
            log.warn("[SessionContext] Milvus collection 初始化失败（写入将降级）: {}", e.getMessage());
        }
    }

    private void ensureIndex() {
        try {
            boolean exists = esClient.indices().exists(
                    new GetIndexRequest(ES_INDEX), RequestOptions.DEFAULT);
            if (exists) {
                return;
            }
            String mapping = """
                    {
                      "mappings": {
                        "properties": {
                          "id": {"type": "keyword"},
                          "sessionId": {"type": "keyword"},
                          "type": {"type": "keyword"},
                          "seq": {"type": "keyword"},
                          "content": {"type": "text", "analyzer": "standard", "search_analyzer": "standard"},
                          "role": {"type": "keyword"},
                          "sourceNode": {"type": "keyword"},
                          "createdAt": {"type": "keyword"}
                        }
                      },
                      "settings": {"number_of_shards": 1, "number_of_replicas": 0}
                    }""";
            esClient.indices().create(new CreateIndexRequest(ES_INDEX).source(mapping, XContentType.JSON),
                    RequestOptions.DEFAULT);
            log.info("[SessionContext] ES index 已自动创建: {}", ES_INDEX);
        } catch (Exception e) {
            log.warn("[SessionContext] ES index 初始化失败: {}", e.getMessage());
        }
    }
}
