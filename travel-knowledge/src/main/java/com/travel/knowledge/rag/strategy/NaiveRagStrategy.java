package com.travel.knowledge.rag.strategy;

import com.travel.knowledge.rag.model.QueryIntent;
import com.travel.knowledge.rag.support.RagFilterBuilder;
import com.travel.knowledge.rag.model.SearchResult;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Naive RAG 策略
 *
 * <p>单路 BM25 文本检索（仅 Elasticsearch），不做向量检索。</p>
 *
 * <p>适用场景：关键词明确、短文本查询。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Component("naiveRag")
@SuppressWarnings("deprecation")
public class NaiveRagStrategy implements RagStrategy {

    private static final String INDEX_NAME = "attraction_index";

    private final RestHighLevelClient esClient;

    @Autowired
    public NaiveRagStrategy(RestHighLevelClient esClient) {
        this.esClient = esClient;
    }

    @Override
    public List<SearchResult> retrieve(QueryIntent intent, int topK) {
        log.info("[NaiveRAG] query={}, intent={}, topK={}", intent.rawQuery(), intent, topK);
        long start = System.currentTimeMillis();

        try {
            var searchRequest = new org.elasticsearch.action.search.SearchRequest(INDEX_NAME);
            var sourceBuilder = new SearchSourceBuilder();
            // F40/P1：与 Hybrid 共用 RagFilterBuilder（multiMatch + city/type filter）。
            sourceBuilder.query(RagFilterBuilder.esQuery(intent, intent.rawQuery()));
            sourceBuilder.size(topK);
            searchRequest.source(sourceBuilder);

            var response = esClient.search(searchRequest, org.elasticsearch.client.RequestOptions.DEFAULT);
            List<SearchResult> results = new ArrayList<>();

            for (SearchHit hit : response.getHits().getHits()) {
                var sourceMap = hit.getSourceAsMap();
                results.add(SearchResult.builder()
                        .docId(hit.getId())
                        .title((String) sourceMap.get("name"))
                        .snippet((String) sourceMap.get("description"))
                        .score(hit.getScore())
                        .imageUrl((String) sourceMap.getOrDefault("imageUrl", ""))
                        .source("es")
                        .build());
            }

            long cost = System.currentTimeMillis() - start;
            log.info("[NaiveRAG] 检索完成, 耗时={}ms, 结果数={}", cost, results.size());
            return results;

        } catch (Exception e) {
            log.error("[NaiveRAG] 检索失败", e);
            return Collections.emptyList();
        }
    }

    @Override
    public String getType() {
        return "naive";
    }
}
