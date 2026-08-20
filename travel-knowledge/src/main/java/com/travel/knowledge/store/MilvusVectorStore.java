package com.travel.knowledge.store;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.SearchResults;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.UpsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M3-3：Milvus 向量库访问适配器（装箱/upsert/search/解析统一封装）。
 * 消灭 AttractionEtlService/HybridRagStrategy/SessionContextService 中的 SDK 裸写重复。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MilvusVectorStore {

    private final MilvusServiceClient client;

    /** F31/F35：float[] → List<Float>（Milvus SDK 客户端校验要求） */
    public static List<Float> box(float[] vector) {
        List<Float> out = new ArrayList<>(vector.length);
        for (float v : vector) {
            out.add(v);
        }
        return out;
    }

    /** 按主键 upsert（失败回退 insert），meta 中 id/vector 之外的字段原样写入 */
    public void upsert(String collection, String docId, List<Float> vector,
                       Map<String, Object> meta) {
        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("id", Collections.singletonList(docId)));
        fields.add(new InsertParam.Field("vector", Collections.singletonList(vector)));
        for (Map.Entry<String, Object> e : meta.entrySet()) {
            fields.add(new InsertParam.Field(e.getKey(), Collections.singletonList(e.getValue())));
        }
        InsertParam insertParam = InsertParam.newBuilder()
                .withCollectionName(collection).withFields(fields).build();
        try {
            UpsertParam upsertParam = UpsertParam.newBuilder()
                    .withCollectionName(collection).withFields(fields).build();
            client.upsert(upsertParam);
        } catch (Exception e) {
            log.warn("[MilvusStore] upsert 失败，回退 insert: {}", e.getMessage());
            client.insert(insertParam);
        }
    }

    /** 向量检索：返回 id/score/outFields 解析结果 */
    public List<SearchRow> search(String collection, List<Float> queryVector, String expr,
                                  int topK, List<String> outFields, MetricType metric) {
        SearchParam.Builder builder = SearchParam.newBuilder()
                .withCollectionName(collection)
                .withVectorFieldName("vector")
                .withVectors(List.of(queryVector))
                .withTopK(topK)
                .withMetricType(metric)
                .withOutFields(outFields);
        if (expr != null && !expr.isBlank()) {
            builder.withExpr(expr);
        }
        SearchParam searchParam = builder.build();
        R<SearchResults> response = client.search(searchParam);
        if (response.getStatus() != R.Status.Success.getCode()) {
            log.error("[MilvusStore] 检索失败: {}", response.getMessage());
            return List.of();
        }
        SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
        List<SearchResultsWrapper.IDScore> scores = wrapper.getIDScore(0);
        List<SearchRow> out = new ArrayList<>(scores.size());
        for (SearchResultsWrapper.IDScore s : scores) {
            Map<String, Object> fields = new LinkedHashMap<>();
            for (String f : outFields) {
                try {
                    fields.put(f, s.get(f));
                } catch (Exception ignored) {
                    fields.put(f, null);
                }
            }
            out.add(new SearchRow(s.getStrID(), s.getScore(), fields));
        }
        return out;
    }

    public record SearchRow(String id, float score, Map<String, Object> fields) {
    }
}
