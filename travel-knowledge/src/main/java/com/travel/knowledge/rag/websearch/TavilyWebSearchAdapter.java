package com.travel.knowledge.rag.websearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.common.util.JsonUtils;
import com.travel.core.guard.CircuitBreaker;
import com.travel.core.guard.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * M9-2：Tavily 直连 HTTP Adapter（WebSearchPort SPI 第二个实现）。
 *
 * <p>与 {@link McpWebSearchAdapter} 同规格：限频（travel-core RateLimiter）+
 * 熔断（"web_search_tavily"）+ 超时（provider.timeoutMs）；失败/超时/限频一律返回
 * empty（调用方静默降级）。API Key 走 api-key-env 环境变量引用，不落 yml。</p>
 */
@Slf4j
@Component
public class TavilyWebSearchAdapter implements WebSearchPort {

    private static final String DEFAULT_BASE_URL = "https://api.tavily.com/search";

    private final WebSearchProperties properties;
    private final HttpClient httpClient;
    private final RateLimiter rateLimiter;
    private final CircuitBreaker circuitBreaker;

    @Autowired
    public TavilyWebSearchAdapter(WebSearchProperties properties) {
        this(properties, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build());
    }

    /** 测试注入：可替换 HttpClient（默认 JDK 内置，零新依赖） */
    TavilyWebSearchAdapter(WebSearchProperties properties, HttpClient httpClient) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.rateLimiter = new RateLimiter(Math.max(1, properties.getRateLimitPerMinute()));
        this.circuitBreaker = new CircuitBreaker(3, 60_000, 30_000);
    }

    @Override
    public Optional<WebSearchResult> search(String query) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        var provider = properties.findProvider("tavily");
        if (provider.isEmpty() || !properties.isProviderEnabled("tavily")) {
            return Optional.empty();
        }
        WebSearchProperties.ProviderProperties p = provider.get();
        String apiKey = p.getApiKey() == null || p.getApiKey().isBlank()
                ? System.getenv(p.getApiKeyEnv())
                : p.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[WebSearch] Tavily 未配置 API Key（apiKey/apiKeyEnv={}），跳过",
                    p.getApiKeyEnv());
            return Optional.empty();
        }
        if (!rateLimiter.tryAcquire("web_search_tavily")) {
            log.warn("[WebSearch] Tavily 限频拒绝（{} 次/分钟）", properties.getRateLimitPerMinute());
            return Optional.empty();
        }
        try {
            return circuitBreaker.call("web_search_tavily", () -> {
                String endpoint = p.getBaseUrl() == null || p.getBaseUrl().isBlank()
                        ? DEFAULT_BASE_URL : p.getBaseUrl();
                String body = JsonUtils.toJson(Map.of(
                        "api_key", apiKey,
                        "query", query,
                        "max_results", 3));
                HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                        .timeout(Duration.ofMillis(Math.max(100, p.getTimeoutMs())))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response;
                try {
                    response = httpClient.send(
                            request, HttpResponse.BodyHandlers.ofString());
                } catch (java.io.IOException | InterruptedException e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    throw new IllegalStateException("Tavily HTTP 调用失败", e);
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("Tavily HTTP " + response.statusCode());
                }
                Optional<WebSearchResult> parsed = parse(response.body());
                parsed.ifPresent(r ->
                        log.info("[WebSearch] Tavily 搜索成功: query={}, title={}", query, r.title()));
                return parsed;
            });
        } catch (Exception e) {
            log.warn("[WebSearch] Tavily 搜索失败/超时，静默降级 empty: query={}, err={}",
                    query, e.getMessage());
            return Optional.empty();
        }
    }

    /** Tavily 响应解析：results[0] 的 title/content/url */
    Optional<WebSearchResult> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode node = JsonUtils.getMapper().readTree(raw);
            JsonNode results = node.path("results");
            if (results.isArray() && !results.isEmpty()) {
                JsonNode first = results.get(0);
                String title = first.path("title").asText("");
                String content = first.path("content").asText("");
                String url = first.path("url").asText("");
                if (!title.isBlank() || !content.isBlank()) {
                    return Optional.of(new WebSearchResult(
                            title, content, url, LocalDateTime.now().toString()));
                }
            }
        } catch (Exception e) {
            log.debug("[WebSearch] Tavily 响应非预期 JSON: {}", e.getMessage());
        }
        return Optional.empty();
    }
}
