package com.travel.crawl.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.core.data.SourceConfidence;
import com.travel.core.guard.CircuitBreaker;
import com.travel.core.util.CityNames;
import com.travel.crawl.config.CrawlProperties;
import com.travel.crawl.model.AttractionRaw;
import com.travel.crawl.util.QuotaGuard;
import com.travel.crawl.util.RequestThrottle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 高德文本搜索源（F110-B，由 AmapClient 重构为 SPI 实现）：
 * adcode 优先（规避中文关键词静默空）、错误码退避重试、城市名空结果自动 adcode 重试、
 * tags 推导、图片 https 规范化、poiId 保留、熔断保护。
 */
@Slf4j
@Component
public class AmapTextSource implements CrawlSource {

    private static final Set<String> IMAGE_HOST_ALLOWED = Set.of(
            "amap.com", "autonavi.com", "restapi.amap.com", "store.is.autonavi.com");

    private final CrawlProperties props;
    private final QuotaGuard quotaGuard;
    private final RequestThrottle throttle;
    private final CircuitBreaker breaker;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public AmapTextSource(CrawlProperties props, QuotaGuard quotaGuard, RequestThrottle throttle) {
        this.props = props;
        this.quotaGuard = quotaGuard;
        this.throttle = throttle;
        this.breaker = new CircuitBreaker(
                props.getCircuit().getFailureThreshold(),
                props.getCircuit().getWindowMs(),
                props.getCircuit().getOpenTimeoutMs());
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    @Override
    public String name() {
        return "amap-text";
    }

    @Override
    public boolean enabled() {
        return props.isEnabled() && props.getAmap().getWebApiKey() != null
                && !props.getAmap().getWebApiKey().isBlank();
    }

    @Override
    public List<AttractionRaw> fetch(CrawlQuery query, int pageNum) {
        if (!quotaGuard.tryAcquire()) {
            throw new IllegalStateException("月度配额已用完，停止抓取");
        }
        throttle.waitIfNeeded();
        try {
            return breaker.call("amap-text", () -> doFetch(query, pageNum));
        } catch (CircuitBreaker.CircuitOpenException e) {
            log.warn("[AmapText] 熔断中，跳过: city={}", query.city());
            return List.of();
        }
    }

    private List<AttractionRaw> doFetch(CrawlQuery query, int pageNum) {
        String region = query.region();
        boolean usedAdcode = region != null && region.matches("\\d{6}");
        int attempts = 0;
        while (true) {
            try {
                String url = buildUrl(region, pageNum);
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("User-Agent", userAgent())
                        .GET().build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    attempts++;
                    if (attempts <= props.getRetry().getMaxRetries()) {
                        backoff(attempts);
                        continue;
                    }
                    log.warn("[AmapText] HTTP {} city={} page={}", resp.statusCode(), query.city(), pageNum);
                    return List.of();
                }
                JsonNode root = mapper.readTree(resp.body());
                if (root == null || !"1".equals(root.path("status").asText())) {
                    String info = root == null ? "null" : root.path("info").asText("");
                    log.warn("[AmapText] 返回非成功: {}", info);
                    // 错误码退避重试（CUQPS/INVALID_USER_IP/网络抖动）
                    attempts++;
                    if (attempts <= props.getRetry().getMaxRetries()) {
                        backoff(attempts);
                        continue;
                    }
                    return List.of();
                }
                long count = root.path("count").asLong(0);
                if (count == 0 && !usedAdcode && query.city() != null) {
                    String adcode = props.getAmap().cityAdcodeMap().get(query.city());
                    if (adcode != null && !adcode.isBlank()) {
                        // F110-B：城市名静默空（中文关键词问题）→ 自动 adcode 重试一次
                        log.warn("[AmapText] 城市名静默空，自动 adcode 重试: city={} adcode={}",
                                query.city(), adcode);
                        region = adcode;
                        usedAdcode = true;
                        continue;
                    }
                }
                return parsePois(root, query.city());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            } catch (Exception e) {
                attempts++;
                log.warn("[AmapText] 搜索失败: city={} page={}, error={}", query.city(), pageNum,
                        e.getMessage());
                if (attempts <= props.getRetry().getMaxRetries()) {
                    backoff(attempts);
                    continue;
                }
                return List.of();
            }
        }
    }

    private void backoff(int attempt) {
        try {
            Thread.sleep(props.getRetry().getRetryBackoffBaseMs() * (1L << (attempt - 1)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 组装 v5 place/text 请求 URL（包可见，便于单元测试断言官方参数） */
    String buildUrl(String region, int pageNum) {
        return props.getAmap().getBaseUrl()
                + "?key=" + props.getAmap().getWebApiKey()
                + "&region=" + enc(region)
                + "&city_limit=true"
                + "&types=" + props.getAmap().getTypes()
                + "&page_num=" + pageNum
                + "&page_size=25"
                + "&show_fields=photos";
    }

    /** 解析 POI JSON（包可见，便于单元测试） */
    List<AttractionRaw> parsePois(JsonNode root, String fallbackCity) {
        List<AttractionRaw> items = new ArrayList<>();
        for (JsonNode poi : root.path("pois")) {
            String name = poi.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            String city = poi.path("cityname").asText("");
            if (city.isBlank() || "[]".equals(city)) {
                city = fallbackCity;
            } else {
                city = CityNames.normalize(city);
            }
            String location = poi.path("location").asText("");
            Double lat = null;
            Double lng = null;
            if (location != null && location.contains(",")) {
                String[] ll = location.split(",");
                try {
                    lng = Double.parseDouble(ll[0].trim());
                    lat = Double.parseDouble(ll[1].trim());
                } catch (NumberFormatException ignored) {
                }
            }
            List<String> imageUrls = parseImageUrls(poi.path("photos"));
            String imageUrl = imageUrls.isEmpty() ? "" : imageUrls.get(0);
            String typeCode = poi.path("type_code").asText(
                    poi.path("typecode").asText(""));
            items.add(new AttractionRaw(
                    poi.path("id").asText(null),
                    name, city, poi.path("adname").asText(""),
                    deriveType(poi.path("type").asText("")),
                    "", lat, lng, poi.path("address").asText(""),
                    "", null, null, null, null,
                    deriveTags(typeCode, poi.path("type").asText("")),
                    "", imageUrl, "amap",
                    SourceConfidence.API, imageUrls,
                    LocalDateTime.now().toString()));
        }
        return items;
    }

    /** 图片候选（最多 3 张，https 规范化 + 域名白名单） */
    private List<String> parseImageUrls(JsonNode photos) {
        List<String> urls = new ArrayList<>();
        if (photos == null || !photos.isArray()) {
            return urls;
        }
        for (JsonNode p : photos) {
            String raw = p.path("url").asText("");
            String normalized = normalizeImageUrl(raw);
            if (normalized != null) {
                urls.add(normalized);
            }
            if (urls.size() >= 3) {
                break;
            }
        }
        return urls;
    }

    /** 图片 URL 规范化：http→https；仅允许 amap/autonavi 图床域名 */
    static String normalizeImageUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String u = url.startsWith("http://") ? "https://" + url.substring(7) : url;
        try {
            String host = URI.create(u).getHost();
            if (host != null) {
                String h = host.toLowerCase(Locale.ROOT);
                if (IMAGE_HOST_ALLOWED.stream().anyMatch(h::endsWith)) {
                    return u;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** typecode 前缀/类型名 → 业务标签（F110-B，配置可覆盖前缀映射） */
    String deriveTags(String typeCode, String typeName) {
        Set<String> tags = new LinkedHashSet<>();
        Map<String, List<String>> map = props.getAmap().typeTagMapAsMap();
        if (map != null) {
            for (Map.Entry<String, List<String>> e : map.entrySet()) {
                if (typeCode != null && typeCode.startsWith(e.getKey())) {
                    tags.addAll(e.getValue());
                    break;
                }
            }
        }
        String t = typeName == null ? "" : typeName;
        if (tags.isEmpty()) {
            if (t.contains("自然") || t.contains("公园") || t.contains("湖泊") || t.contains("山")) {
                tags.add("自然");
            }
            if (t.contains("寺庙") || t.contains("教堂") || t.contains("道观") || t.contains("宗教")) {
                tags.add("宗教");
            }
            if (t.contains("博物馆") || t.contains("展览")) {
                tags.add("博物馆");
            }
            if (t.contains("地标") || t.contains("景区") || t.contains("遗址")) {
                tags.add("地标");
            }
            if (t.contains("主题") || t.contains("乐园") || t.contains("游乐")) {
                tags.add("亲子");
            }
        }
        if (tags.isEmpty()) {
            tags.add("景点");
        }
        try {
            return mapper.writeValueAsString(new ArrayList<>(tags));
        } catch (Exception e) {
            return "[\"景点\"]";
        }
    }

    private static String deriveType(String poiType) {
        String t = poiType == null ? "" : poiType;
        if (t.contains("自然") || t.contains("公园") || t.contains("湖泊") || t.contains("山")) {
            return "NATURE";
        }
        if (t.contains("主题") || t.contains("游乐") || t.contains("乐园")) {
            return "FAMILY";
        }
        if (t.contains("美食") || t.contains("餐厅") || t.contains("小吃")) {
            return "FOOD";
        }
        if (t.contains("购物") || t.contains("商业")) {
            return "SHOPPING";
        }
        return "CULTURE";
    }

    private String userAgent() {
        List<String> uas = props.getUserAgents();
        if (uas == null || uas.isEmpty()) {
            return "travel-planner-crawler/1.0";
        }
        return uas.get((int) (System.currentTimeMillis() % uas.size()));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
