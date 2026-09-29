package com.travel.knowledge.rag.websearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.common.util.JsonUtils;
import com.travel.core.guard.CircuitBreaker;
import com.travel.core.guard.RateLimiter;
import com.travel.knowledge.rag.support.RagRoutingMetrics;
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
    private final TavilyQuotaManager quotaManager;
    /** AF-2a：上游抖动观测（retry_total/retry_recovered，可空=测试容错，同 quotaManager 惯例） */
    private final RagRoutingMetrics metrics;

    @Autowired
    public TavilyWebSearchAdapter(WebSearchProperties properties, TavilyQuotaManager quotaManager,
                                  RagRoutingMetrics metrics) {
        this(properties, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build(), quotaManager, metrics);
    }

    /** 测试注入：可替换 HttpClient（默认 JDK 内置，零新依赖） */
    TavilyWebSearchAdapter(WebSearchProperties properties, HttpClient httpClient,
                           TavilyQuotaManager quotaManager, RagRoutingMetrics metrics) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.quotaManager = quotaManager;
        this.metrics = metrics;
        this.rateLimiter = new RateLimiter(Math.max(1, properties.getRateLimitPerMinute()));
        this.circuitBreaker = new CircuitBreaker(3, 60_000, 30_000);
    }

    @Override
    public Optional<WebSearchResult> search(String query) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        // J-2d：月度配额前置检查（exhausted 时静默降级）
        if (quotaManager != null && !quotaManager.canSearch()) {
            log.debug("[TavilyQuota] 配额不足，跳过搜索: remaining=0");
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
                } catch (java.io.IOException first) {
                    // AF-2a：SNI 选择性阻断缓解（G2）——IOException 后 sleep 200ms 同请求重发一次
                    //（重发重新解析 DNS≈2/3 概率换 IP；E-52：重试不重复扣配额——consume 仍在成功解析后）；
                    // InterruptedException 不重试（取消信号），第二次仍 IOException → 既有 IllegalStateException（failover 链不变）
                    if (metrics != null) {
                        metrics.recordTavilyRetry();
                    }
                    try {
                        Thread.sleep(200);
                        response = httpClient.send(
                                request, HttpResponse.BodyHandlers.ofString());
                        if (metrics != null) {
                            metrics.recordTavilyRetryRecovered();
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Tavily HTTP 调用失败", first);
                    } catch (java.io.IOException second) {
                        throw new IllegalStateException("Tavily HTTP 调用失败", second);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Tavily HTTP 调用失败", e);
                }
                if (response.statusCode() == 432) {
                    // 2026-09-30 审计修复：432=计划额度耗尽（Redis 计数与 API 实际口径可能不一致——
                    // 本机 SNI 选择性阻断期间失败的调用未计数，实际消耗高于 Redis 读数）。
                    // 对齐 J-2c exhausted 语义：静默降级 empty（非错误），并把 Redis 计数补推到硬限，
                    // 使 canSearch()/currentMode() 立即进入 exhausted 态（后续调用零外发）。
                    if (quotaManager != null) {
                        quotaManager.markExternallyExhausted();
                    }
                    log.warn("[WebSearch] Tavily 432 计划额度耗尽（API 实际口径）——标记 exhausted 并静默降级");
                    return Optional.empty();
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("Tavily HTTP " + response.statusCode());
                }
                Optional<WebSearchResult> parsed = parse(response.body());
                // J-2d：搜索成功消耗 1 credit
                if (parsed.isPresent() && quotaManager != null) {
                    quotaManager.consume();
                }
                parsed.ifPresent(r ->
                        log.info("[WebSearch] Tavily 搜索成功: query={}, title={}", query, r.title()));
                return parsed;
            });
        } catch (Exception e) {
            log.warn("[WebSearch] Tavily 搜索失败/超时，静默降级 empty: query={}, err={}: {}",
                    query, e.getClass().getName(), e.getMessage());
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
