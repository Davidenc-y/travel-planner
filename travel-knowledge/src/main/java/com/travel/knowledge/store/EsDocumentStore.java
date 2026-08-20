package com.travel.knowledge.store;

import lombok.RequiredArgsConstructor;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.xcontent.XContentType;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * M3-3：Elasticsearch 文档访问适配器（写入/检索统一封装），
 * 消灭 AttractionEtlService/HybridRagStrategy/NaiveRagStrategy/SessionContextService 的裸写重复。
 */
@Component
@RequiredArgsConstructor
public class EsDocumentStore {

    private final RestHighLevelClient client;

    public void index(String index, String docId, Map<String, Object> doc) throws Exception {
        IndexRequest request = new IndexRequest(index)
                .id(docId)
                .source(com.travel.common.util.JsonUtils.toJson(doc), XContentType.JSON);
        client.index(request, RequestOptions.DEFAULT);
    }

    public SearchHit[] search(String index, QueryBuilder query, int size) throws Exception {
        SearchRequest searchRequest = new SearchRequest(index);
        SearchSourceBuilder builder = new SearchSourceBuilder();
        builder.query(query);
        builder.size(size);
        searchRequest.source(builder);
        SearchResponse response = client.search(searchRequest, RequestOptions.DEFAULT);
        return response.getHits().getHits();
    }
}
