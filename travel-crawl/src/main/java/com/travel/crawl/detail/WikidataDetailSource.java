package com.travel.crawl.detail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.core.data.SourceConfidence;
import com.travel.core.guard.CircuitBreaker;
import com.travel.crawl.config.CrawlProperties;
import com.travel.crawl.model.AttractionRaw;
import com.travel.crawl.util.LruTtlCache;
import com.travel.crawl.util.RequestThrottle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Wikidata 短描述补充源（F115 T1）：
 * wbsearchentities(zh) → 实体 id → Special:EntityData JSON → zh/en 描述 + 别名
 * + P625 坐标 + P856 官网。
 *
 * <p>合规：Wikidata 结构化数据 CC0；UA 必须含项目名+联系方式（WD 政策）；
 * 限频 ≥1 req/s（默认 1.2s）；本地 LRU 缓存 7 天；任何失败降级返回空（不阻断主链路）；
 * 复用 travel-core CircuitBreaker 熔断保护。</p>
 */
@Slf4j
@Component
@Order(1)
public class WikidataDetailSource implements DetailSource {

    /** 可注入的请求器（测试桩；默认 JDK HttpClient 实现） */
    public interface Fetcher {
        String fetch(String url) throws Exception;
    }

    /** 未命中字段（Wikidata 不提供，标记供观测；人工补录兜底） */
    private static final List<String> NOT_FOUND_FIELDS =
            List.of("openHours", "ticketPrice", "freeEntry", "rating", "ratingCount",
                    "recommendedDuration");

    private final CrawlProperties props;
    private final RequestThrottle throttle;
    private final CircuitBreaker breaker;
    private final Fetcher fetcher;
    private final LruTtlCache<String, DetailEnrichment> cache;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 搜索命中即携带描述时的轻量结果 */
    record SearchHit(String id, String description) {
    }

    @Autowired
    public WikidataDetailSource(CrawlProperties props) {
        this(props, null);
    }

    public WikidataDetailSource(CrawlProperties props, Fetcher fetcher) {
        this.props = props;
        CrawlProperties.Wikidata wd = props.getDetail().getWikidata();
        this.throttle = new RequestThrottle(wd.getMinIntervalMs(), 0, 0);
        this.breaker = new CircuitBreaker(
                props.getCircuit().getFailureThreshold(),
                props.getCircuit().getWindowMs(),
                props.getCircuit().getOpenTimeoutMs());
        this.fetcher = fetcher != null ? fetcher : this::fetchDefault;
        this.cache = wd.isCacheEnabled()
                ? new LruTtlCache<>(Math.max(1, wd.getCacheMaxSize()),
                Math.max(1, wd.getCacheTtlDays()) * 86_400_000L)
                : null;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(wd.getConnectTimeoutMs())).build();
    }

    @Override
    public String name() {
        return "wikidata";
    }

    @Override
    public boolean enabled() {
        CrawlProperties.Wikidata wd = props.getDetail().getWikidata();
        return wd.isEnabled() && wd.getBaseUrl() != null && !wd.getBaseUrl().isBlank();
    }

    @Override
    public DetailEnrichment enrich(AttractionRaw item) {
        if (item == null || item.name() == null || item.name().isBlank()) {
            return DetailEnrichment.empty();
        }
        String key = cacheKey(item);
        if (cache != null) {
            DetailEnrichment hit = cache.get(key);
            if (hit != null) {
                log.debug("[Wikidata] 缓存命中: name={}", item.name());
                return hit;
            }
        }
        try {
            DetailEnrichment result = breaker.call(name(), () -> {
                try {
                    return doEnrich(item);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            if (cache != null && result.hasAny()) {
                cache.put(key, result);
            }
            return result;
        } catch (CircuitBreaker.CircuitOpenException e) {
            log.warn("[Wikidata] 熔断中，跳过: name={}", item.name());
            return DetailEnrichment.empty();
        } catch (Exception e) {
            log.warn("[Wikidata] 查询失败（原样降级）: name={}, error={}", item.name(), e.getMessage());
            return DetailEnrichment.empty();
        }
    }

    @Override
    public boolean mayRequireNetwork(AttractionRaw item) {
        if (item == null || item.name() == null || item.name().isBlank()) {
            return false;
        }
        return cache == null || cache.get(cacheKey(item)) == null;
    }

    private DetailEnrichment doEnrich(AttractionRaw item) throws Exception {
        SearchHit hit = searchEntity(item.name());
        if (hit == null || hit.id() == null || hit.id().isBlank()) {
            return DetailEnrichment.empty();
        }
        // 搜索命中直接携带 zh 描述 → 免实体请求（减半请求量，规避慢网络）
        if (hit.description() != null && !hit.description().isBlank()) {
            List<String> urls = new ArrayList<>();
            urls.add(base() + "/wiki/" + hit.id());
            String desc = hit.description().length() > 500
                    ? hit.description().substring(0, 500) : hit.description();
            return new DetailEnrichment(desc, null, null, null, urls,
                    NOT_FOUND_FIELDS, SourceConfidence.ENRICH);
        }
        return parseEntity(fetchEntity(hit.id()), hit.id());
    }

    /** wbsearchentities（zh）→ 第一个实体 id + 搜索描述（包可见，便于单测） */
    SearchHit searchEntity(String name) throws Exception {
        String url = base() + "/w/api.php?action=wbsearchentities&format=json&language=zh&uselang=zh"
                + "&type=item&limit=" + props.getDetail().getWikidata().getMaxSearchResults()
                + "&search=" + enc(name);
        JsonNode root = mapper.readTree(fetcher.fetch(url));
        for (JsonNode hit : root.path("search")) {
            String id = hit.path("id").asText("");
            if (!id.isBlank()) {
                return new SearchHit(id, hit.path("description").asText(""));
            }
        }
        return null;
    }

    String fetchEntity(String entityId) throws Exception {
        return fetcher.fetch(base() + "/wiki/Special:EntityData/" + entityId + ".json");
    }

    /** 解析实体 JSON：zh/en 描述 + 别名 + P625 坐标 + P856 官网（包可见，便于单测） */
    DetailEnrichment parseEntity(String body, String entityId) throws Exception {
        JsonNode root = mapper.readTree(body);
        JsonNode entities = root.path("entities");
        if (!entities.isObject() || entities.isEmpty()) {
            return DetailEnrichment.empty();
        }
        Iterator<String> it = entities.fieldNames();
        String id = it.next();
        JsonNode e = entities.get(id);
        String description = firstText(e.path("descriptions"), "zh", "en");
        if (description != null && description.length() > 500) {
            description = description.substring(0, 500);
        }
        List<String> aliases = aliasTexts(e.path("aliases"), 3);
        Double lat = null;
        Double lng = null;
        JsonNode p625 = e.path("claims").path("P625");
        if (p625.isArray() && !p625.isEmpty()) {
            JsonNode val = p625.get(0).path("mainsnak").path("datavalue").path("value");
            if (val.isObject() && val.has("latitude") && val.has("longitude")) {
                lat = val.path("latitude").asDouble();
                lng = val.path("longitude").asDouble();
            }
        }
        String officialUrl = firstClaimUrl(e.path("claims").path("P856"));
        List<String> urls = new ArrayList<>();
        urls.add(base() + "/wiki/" + id);
        if (officialUrl != null && !officialUrl.isBlank()) {
            urls.add(officialUrl);
        }
        log.debug("[Wikidata] 实体解析: id={}, desc={}, aliases={}, lat={}, lng={}, url={}",
                id, description, aliases, lat, lng, officialUrl);
        return new DetailEnrichment(description, lat, lng, officialUrl, urls,
                NOT_FOUND_FIELDS, SourceConfidence.ENRICH);
    }

    private String fetchDefault(String url) throws Exception {
        throttle.waitIfNeeded();
        String mode = props.getDetail().getWikidata().getFetchMode() == null
                ? "auto" : props.getDetail().getWikidata().getFetchMode().trim().toLowerCase();
        if ("curl".equals(mode)) {
            return fetchWithCurl(url);
        }
        if ("jdk".equals(mode)) {
            return fetchWithHttpClient(url);
        }
        try {
            return fetchWithHttpClient(url);
        } catch (Exception e) {
            CrawlProperties.Wikidata wd = props.getDetail().getWikidata();
            if (!wd.isFallbackCurlEnabled()) {
                throw e;
            }
            log.warn("[Wikidata] JDK HttpClient 失败，改用 curl 降级: error={}", e.getMessage());
            return fetchWithCurl(url);
        }
    }

    private String fetchWithHttpClient(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(props.getDetail().getWikidata().getTimeoutMs()))
                .header("User-Agent", props.getDetail().getWikidata().getUserAgent())
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    /** curl 降级：与博客“requests→curl 备用”同思路，规避 Java TLS 指纹被部分网络丢弃的问题 */
    private String fetchWithCurl(String url) throws Exception {
        CrawlProperties.Wikidata wd = props.getDetail().getWikidata();
        ProcessBuilder pb = new ProcessBuilder(wd.getCurlPath(),
                "-s", "-m", String.valueOf(Math.max(1, wd.getCurlTimeoutMs() / 1000)),
                "--retry", "2", "--retry-delay", "1", "--retry-all-errors",
                "-A", wd.getUserAgent(),
                "-H", "Accept: application/json",
                url);
        Process p = pb.start();
        String body;
        String err;
        try (var in = p.getInputStream()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (var ein = p.getErrorStream()) {
            err = new String(ein.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!p.waitFor(wd.getCurlTimeoutMs() + 5000, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("curl 超时");
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException("curl exit=" + p.exitValue()
                    + " err=" + (err == null ? "" : err.trim()));
        }
        return body;
    }

    private String cacheKey(AttractionRaw item) {
        return item.name().trim() + "|" + (item.city() == null ? "" : item.city());
    }

    private String base() {
        String b = props.getDetail().getWikidata().getBaseUrl();
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    private static String firstText(JsonNode mapNode, String... langs) {
        if (mapNode == null || !mapNode.isObject()) {
            return null;
        }
        for (String lang : langs) {
            String s = mapNode.path(lang).path("value").asText("");
            if (!s.isBlank()) {
                return s.trim();
            }
        }
        return null;
    }

    private static List<String> aliasTexts(JsonNode aliases, int max) {
        List<String> out = new ArrayList<>();
        if (aliases == null || !aliases.isObject()) {
            return out;
        }
        for (String lang : new String[]{"zh", "en"}) {
            JsonNode arr = aliases.path(lang);
            if (arr.isArray()) {
                for (JsonNode a : arr) {
                    String v = a.path("value").asText("");
                    if (!v.isBlank() && !out.contains(v.trim())) {
                        out.add(v.trim());
                        if (out.size() >= max) {
                            return out;
                        }
                    }
                }
            }
        }
        return out;
    }

    private static String firstClaimUrl(JsonNode claims) {
        if (claims == null || !claims.isArray() || claims.isEmpty()) {
            return null;
        }
        JsonNode v = claims.get(0).path("mainsnak").path("datavalue").path("value");
        if (v == null || v.isMissingNode() || v.isNull()) {
            return null;
        }
        if (v.isObject() && v.has("url")) {
            return v.path("url").asText(null);
        }
        return v.isTextual() ? v.asText() : null;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
