package com.travel.knowledge.memory;

import com.travel.knowledge.store.MilvusVectorStore;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.xcontent.XContentType;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 会话知识写入/初始化域协作件（MM-4.2，自 SessionContextService 原样迁出）。
 *
 * <p>承载写入面：切片规范化 → Embedding → Milvus/ES 双写（幂等：chunkId 先删后插，
 * ES docId 覆盖，F79 幂等命中跳过）+ ES index 幂等自建；Milvus collection 自建经
 * {@link ContextRetriever#ensureCollection()}（4.1 起随检索自愈同源）。方法体与迁出前
 * 逐字一致。类名沿用方案 MM-4 白名单（"装配"指切片→存储文档的组装），特此说明承载
 * 内容为写入/初始化域。</p>
 */
@Slf4j
@Component
public class ContextAssembler {

    private static final String MILVUS_COLLECTION = "session_context";
    private static final String ES_INDEX = "session_context";
    private static final int CONTENT_MAX_CHARS = 2000;

    private final MilvusServiceClient milvusClient;
    private final RestHighLevelClient esClient;
    private final EmbeddingModel embeddingModel;
    // MM-4.1：Milvus collection 幂等自建（随检索自愈同源）
    private final ContextRetriever contextRetriever;

    public ContextAssembler(MilvusServiceClient milvusClient,
                            RestHighLevelClient esClient,
                            EmbeddingModel embeddingModel,
                            ContextRetriever contextRetriever) {
        this.milvusClient = milvusClient;
        this.esClient = esClient;
        this.embeddingModel = embeddingModel;
        this.contextRetriever = contextRetriever;
    }

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
        contextRetriever.ensureCollection();
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

    // ==================== 内部实现 ====================

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

    /** 查询/写入向量化（与 ContextRetriever#embed 同源口径；写入域自用）。 */
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
