package com.travel.knowledge.etl;

import com.travel.common.entity.Attraction;
import com.travel.common.util.JsonUtils;
import com.travel.knowledge.repository.AttractionMapper;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.dml.InsertParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.xcontent.XContentType;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 景点 ETL 管道服务
 *
 * <p>数据流向：MySQL → Embedding → Milvus + ES 双写</p>
 *
 * <pre>
 * 1. 查询 MySQL 未索引景点（indexed=0）
 * 2. 每个景点：
 *    a. 构建内容文本（name + city + type + description + ...）
 *    b. EmbeddingModel 生成向量
 *    c. 写入 Milvus（向量 + metadata）
 *    d. 写入 ES（全文索引）
 *    e. 更新 MySQL indexed=1
 * 3. 返回成功统计
 * </pre>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Service
@SuppressWarnings("deprecation")
@RequiredArgsConstructor
public class AttractionEtlService {

    private static final String MILVUS_COLLECTION = "attraction_vectors";
    private static final String ES_INDEX = "attraction_index";

    private final AttractionMapper attractionMapper;
    private final EmbeddingModel embeddingModel;
    private final MilvusServiceClient milvusClient;
    private final RestHighLevelClient esClient;

    /**
     * 全量 ETL：处理所有未索引景点
     *
     * @return 成功处理数量
     */
    public int etlAll() {
        List<Attraction> all = attractionMapper.selectList(null);
        log.info("开始全量 ETL, 共 {} 条景点", all.size());
        return processBatch(all);
    }

    /**
     * 增量 ETL：仅处理未索引景点
     *
     * @return 成功处理数量
     */
    public int etlUnindexed() {
        List<Attraction> unindexed = attractionMapper.findUnindexed(500);
        log.info("开始增量 ETL, 共 {} 条未索引景点", unindexed.size());
        return processBatch(unindexed);
    }

    /**
     * 单条 ETL：处理指定景点
     *
     * @param attraction 景点实体
     * @return 是否成功
     */
    public boolean etlOne(Attraction attraction) {
        try {
            String content = buildContent(attraction);
            String docId = String.valueOf(attraction.getId());

            // 1. 生成 Embedding
            var embedResponse = embeddingModel.embedForResponse(List.of(content));
            float[] vector = embedResponse.getResults().get(0).getOutput();

            // 2. 写入 Milvus
            insertToMilvus(docId, vector, attraction);

            // 3. 写入 ES
            insertToEs(docId, attraction, content);

            // 4. 标记 MySQL 已索引
            attractionMapper.markIndexed(attraction.getId());

            log.debug("ETL 成功: id={}, name={}", attraction.getId(), attraction.getName());
            return true;

        } catch (Exception e) {
            log.error("ETL 失败: id={}, name={}, error={}",
                    attraction.getId(), attraction.getName(), e.getMessage());
            return false;
        }
    }

    /**
     * 定时增量 ETL（每天凌晨 3 点）
     */
    @Scheduled(cron = "${travel.etl.schedule-cron:0 0 3 * * ?}")
    public void scheduledEtl() {
        log.info("定时 ETL 触发");
        int count = etlUnindexed();
        log.info("定时 ETL 完成: 处理 {} 条", count);
    }

    /**
     * 获取 ETL 统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", attractionMapper.countAll());
        stats.put("indexed", attractionMapper.countIndexed());
        stats.put("unindexed", attractionMapper.countAll() - attractionMapper.countIndexed());
        return stats;
    }

    // ==================== 内部方法 ====================

    private int processBatch(List<Attraction> attractions) {
        int success = 0;
        int fail = 0;
        for (Attraction a : attractions) {
            if (etlOne(a)) {
                success++;
            } else {
                fail++;
            }
        }
        log.info("ETL 批次完成: 总计={}, 成功={}, 失败={}", attractions.size(), success, fail);
        return success;
    }

    /**
     * 构建景点内容文本（用于 Embedding）
     */
    private String buildContent(Attraction a) {
        return String.format("""
                名称：%s
                城市：%s
                类型：%s
                描述：%s
                地址：%s
                开放时间：%s
                门票：%s 元
                评分：%s
                推荐时长：%s
                标签：%s""",
                a.getName(), a.getCity(), a.getType(), a.getDescription(),
                a.getAddress(), a.getOpenHours(), a.getTicketPrice(),
                a.getRating(), a.getRecommendedDuration(), a.getTags());
    }

    /**
     * 写入 Milvus 向量库
     */
    private void insertToMilvus(String docId, float[] vector, Attraction a) {
        List<String> tags = JsonUtils.parseList(a.getTags(), String.class);

        // F31：Milvus SDK 要求 FLOAT_VECTOR 字段值为 List<Float>（单行即
        // Collections.singletonList(List<Float>)），直接传 float[] 会在客户端校验阶段抛
        // ParamException "Float vector field's value type must be List<Float>"，
        // 被 SDK 重试掩盖但每次写入都会刷 ERROR 堆栈（TC-13 控制台大量报错根因）。
        List<Float> vectorList = new ArrayList<>(vector.length);
        for (float v : vector) {
            vectorList.add(v);
        }

        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("id", Collections.singletonList(docId)));
        fields.add(new InsertParam.Field("vector", Collections.singletonList(vectorList)));
        fields.add(new InsertParam.Field("name", Collections.singletonList(a.getName())));
        fields.add(new InsertParam.Field("city", Collections.singletonList(a.getCity())));
        fields.add(new InsertParam.Field("type", Collections.singletonList(a.getType())));
        fields.add(new InsertParam.Field("tags", Collections.singletonList(a.getTags())));
        // F32：Milvus attraction_vectors 中 rating/ticketPrice 为 FLOAT，
        // SDK 客户端校验要求 java.lang.Float；传 Double 会抛
        // ParamException "Float field value type must be Float"（被重试掩盖，但刷 ERROR 堆栈）。
        fields.add(new InsertParam.Field("rating",
                Collections.singletonList(a.getRating() != null ? a.getRating().floatValue() : 0.0f)));
        fields.add(new InsertParam.Field("ticketPrice",
                Collections.singletonList(a.getTicketPrice() != null ? a.getTicketPrice().floatValue() : 0.0f)));
        // F44/P3：free_entry 入 Milvus（INT64），供检索侧 freeOnly 过滤。
        fields.add(new InsertParam.Field("free_entry",
                Collections.singletonList(a.getFreeEntry() != null ? a.getFreeEntry().longValue() : 0L)));
        fields.add(new InsertParam.Field("createdAt",
                Collections.singletonList(a.getCreatedAt() != null ? a.getCreatedAt().toString() : "")));

        InsertParam insertParam = InsertParam.newBuilder()
                .withCollectionName(MILVUS_COLLECTION)
                .withFields(fields)
                .build();

        milvusClient.insert(insertParam);
        log.debug("Milvus 写入成功: id={}", docId);
    }

    /**
     * 写入 Elasticsearch 全文索引
     */
    private void insertToEs(String docId, Attraction a, String content) throws Exception {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", docId);
        doc.put("name", a.getName());
        doc.put("city", a.getCity());
        doc.put("type", a.getType());
        doc.put("description", a.getDescription());
        doc.put("tags", JsonUtils.parseList(a.getTags(), String.class));
        doc.put("rating", a.getRating() != null ? a.getRating().doubleValue() : 0.0);
        doc.put("ticketPrice", a.getTicketPrice() != null ? a.getTicketPrice().doubleValue() : 0.0);
        // F44/P3：free_entry 入 ES（integer），供检索侧 freeOnly 过滤。
        doc.put("free_entry", a.getFreeEntry() != null ? a.getFreeEntry() : 0);
        doc.put("createdAt", a.getCreatedAt() != null ? a.getCreatedAt().toString() : "");

        IndexRequest request = new IndexRequest(ES_INDEX)
                .id(docId)
                .source(JsonUtils.toJson(doc), XContentType.JSON);

        esClient.index(request, org.elasticsearch.client.RequestOptions.DEFAULT);
        log.debug("ES 写入成功: id={}", docId);
    }
}
