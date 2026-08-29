package com.travel.knowledge.rag.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.common.util.JsonUtils;
import com.travel.knowledge.rag.model.SearchResult;
import com.travel.knowledge.rag.support.RagRoutingMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * M4-6：DashScope 精排 Reranker（type=dashscope 时生效，默认不生效）。
 *
 * <p>POST {@code /api/v1/services/rerank/text-rerank/text-rerank}（Bearer
 * {@code spring.ai.dashscope.api-key}，model=gte-rerank-v2，return_documents=false），
 * 读响应 {@code output.results[].index} 映射回原候选列表。设计要点：</p>
 * <ul>
 *   <li><b>fail-open</b>：HTTP/超时/限频/解析任何失败按原顺序截断 topK 返回 + WARN +
 *       fallback 计数（不做熔断，保持简单）；</li>
 *   <li>2s 硬性超时（{@link CompletableFuture#orTimeout}，虚拟线程）；</li>
 *   <li>{@code Semaphore(2)} 限频（tryAcquire 带超时等待，防并发穿透）；</li>
 *   <li>文档文本 = title + snippet；HTTP 交换点 {@link #exchange(String)} 可覆写（单测替身）。</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "travel.rag.rerank.type", havingValue = "dashscope")
public class DashScopeReranker implements Reranker {

    private static final String ENDPOINT =
            "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    /** 精排专用虚拟线程池（orTimeout 超时后底层调用可被丢弃） */
    private static final ExecutorService RERANK_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /** 全局并发限频（DashScope rerank 并发上限 2） */
    private static final Semaphore CONCURRENCY = new Semaphore(2);

    private final RerankProperties properties;
    private final RagRoutingMetrics metrics;
    /** M7 Batch 4：模型名配置化（travel.rag.rerank.model，默认不变 gte-rerank-v2） */
    private final String model;
    private final String apiKey;
    private final RestClient restClient;

    public DashScopeReranker(RerankProperties properties,
                             RagRoutingMetrics metrics,
                             @Value("${travel.rag.rerank.model:gte-rerank-v2}") String model,
                             @Value("${spring.ai.dashscope.api-key:}") String apiKey) {
        this.properties = properties;
        this.metrics = metrics;
        this.model = model;
        this.apiKey = apiKey;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.getTimeoutMs());
        factory.setReadTimeout((int) properties.getTimeoutMs());
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public List<SearchResult> rerank(String query, List<SearchResult> candidates, int topK) {
        if (candidates == null || candidates.isEmpty() || topK <= 0) {
            return List.of();
        }
        try {
            if (!CONCURRENCY.tryAcquire(properties.getTimeoutMs(), TimeUnit.MILLISECONDS)) {
                return failOpen(candidates, topK, "并发限频等待超时");
            }
            try {
                return CompletableFuture
                        .supplyAsync(() -> doRerank(query, candidates, topK), RERANK_EXECUTOR)
                        .orTimeout(properties.getTimeoutMs(), TimeUnit.MILLISECONDS)
                        .join();
            } finally {
                CONCURRENCY.release();
            }
        } catch (Exception e) {
            return failOpen(candidates, topK, e.getMessage());
        }
    }

    private List<SearchResult> doRerank(String query, List<SearchResult> candidates, int topK) {
        List<String> documents = candidates.stream()
                .map(r -> (safe(r.getTitle()) + "\n" + safe(r.getSnippet())).strip())
                .toList();
        Map<String, Object> body = Map.of(
                "model", model,
                "input", Map.of("query", query == null ? "" : query, "documents", documents),
                "parameters", Map.of("return_documents", false, "top_n", topK));
        String response = exchange(JsonUtils.toJson(body));
        JsonNode results = parseResults(response);
        List<SearchResult> out = new ArrayList<>(results.size());
        for (JsonNode r : results) {
            int idx = r.path("index").asInt(-1);
            if (idx >= 0 && idx < candidates.size()) {
                out.add(candidates.get(idx));
            }
        }
        return out.size() <= topK ? out : out.subList(0, topK);
    }

    private static JsonNode parseResults(String response) {
        try {
            JsonNode results = JsonUtils.getMapper().readTree(response).path("output").path("results");
            if (!results.isArray()) {
                throw new IllegalStateException("rerank 响应缺少 output.results: " + response);
            }
            return results;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("rerank 响应解析失败: " + e.getMessage(), e);
        }
    }

    /** HTTP 交换点（protected 便于单测覆写替身，不起真实网络） */
    protected String exchange(String jsonBody) {
        return restClient.post()
                .uri(ENDPOINT)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonBody)
                .retrieve()
                .body(String.class);
    }

    /** fail-open：原顺序截断 topK + WARN + fallback 计数 */
    private List<SearchResult> failOpen(List<SearchResult> candidates, int topK, String reason) {
        metrics.recordRerankFallback();
        log.warn("[DashScopeReranker] rerank 失败，fail-open 原顺序截断 topK={}: {}", topK, reason);
        return candidates.stream().limit(topK).toList();
    }

    private static String safe(String v) {
        return v == null ? "" : v;
    }
}
