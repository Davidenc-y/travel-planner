package com.travel.crawl.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.crawl.config.CrawlProperties;
import com.travel.crawl.model.CrawlItem;
import com.travel.crawl.util.MonthlyQuotaGuard;
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
import java.util.ArrayList;
import java.util.List;

/** 高德 POI 客户端（F104 P0）：place/text 分页搜索 + 节流 + 配额。 */
@Slf4j
@Component
public class AmapClient {

    private final CrawlProperties props;
    private final MonthlyQuotaGuard quotaGuard;
    private final RequestThrottle throttle;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public AmapClient(CrawlProperties props, MonthlyQuotaGuard quotaGuard, RequestThrottle throttle) {
        this.props = props;
        this.quotaGuard = quotaGuard;
        this.throttle = throttle;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    /**
     * 搜索某城市一页景点。
     *
     * @return 解析后的条目（空表示该页无数据）
     */
    public List<CrawlItem> search(String city, int pageNum) {
        if (!quotaGuard.tryAcquire()) {
            throw new IllegalStateException("月度配额已用完，停止抓取");
        }
        throttle.waitIfNeeded();
        try {
            String url = buildUrl(city, pageNum);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", userAgent())
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("[Amap] HTTP {} for city={} page={}", resp.statusCode(), city, pageNum);
                return List.of();
            }
            return parsePois(resp.body(), city);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            log.warn("[Amap] 搜索失败: city={} page={}, error={}", city, pageNum, e.getMessage());
            return List.of();
        }
    }

    /** 组装 v5 place/text 请求 URL（包可见，便于单元测试断言官方参数） */
    String buildUrl(String city, int pageNum) {
        return props.getAmap().getBaseUrl()
                + "?key=" + props.getAmap().getWebApiKey()
                + "&region=" + enc(city)
                + "&city_limit=true"
                + "&types=" + props.getAmap().getTypes()
                + "&page_num=" + pageNum
                + "&page_size=25"
                + "&show_fields=photos";
    }

    /** 解析 POI JSON（包可见，便于单元测试） */
    List<CrawlItem> parsePois(String body, String fallbackCity) throws Exception {
        JsonNode root = mapper.readTree(body);
        if (root == null || !"1".equals(root.path("status").asText())) {
            log.warn("[Amap] 返回非成功: {}", root == null ? "null" : root.path("info").asText());
            return List.of();
        }
        List<CrawlItem> items = new ArrayList<>();
        for (JsonNode poi : root.path("pois")) {
            String name = poi.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            String city = poi.path("cityname").asText("");
            if (city.isBlank() || "[]".equals(city)) {
                city = fallbackCity;
            } else {
                // F108：AMap v5 cityname 返回"北京市"等带"市"后缀，与库内"北京"不一致，
                // 会导致按 name+city 的 upsert 去重失效（重复插入）与 RAG 城市过滤失配。
                city = normalizeCity(city);
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
            String photo = "";
            JsonNode photos = poi.path("photos");
            if (photos.isArray() && photos.size() > 0) {
                photo = photos.get(0).path("url").asText("");
            }
            items.add(new CrawlItem(
                    name, city, poi.path("adname").asText(""),
                    deriveType(poi.path("type").asText("")),
                    "", lat, lng, poi.path("address").asText(""),
                    "", null, null, null, null,
                    "[]", "", photo, "amap"));
        }
        return items;
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

    /** 城市名归一化：去掉末尾"市"（直辖市/地级市短名，与库内清单一致）；自治州/地区等保留原样 */
    static String normalizeCity(String city) {
        String c = city == null ? "" : city.trim();
        if (c.endsWith("市") && c.length() > 2) {
            return c.substring(0, c.length() - 1);
        }
        return c;
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
