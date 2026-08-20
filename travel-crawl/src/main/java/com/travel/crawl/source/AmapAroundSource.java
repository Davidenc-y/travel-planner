package com.travel.crawl.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.core.util.CityNames;
import com.travel.crawl.config.CrawlProperties;
import com.travel.crawl.model.AttractionRaw;
import com.travel.crawl.util.QuotaGuard;
import com.travel.crawl.util.RequestThrottle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 高德周边搜索源（F110-B 备胎）：location+radius，规避文本搜索中文关键词问题；
 * 默认关闭（travel.crawl.sources.around-enabled=true 时启用），城市中心坐标来自
 * travel.crawl.sources.city-centers 配置。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "travel.crawl.sources.around-enabled", havingValue = "true")
public class AmapAroundSource implements CrawlSource {

    private final CrawlProperties props;
    private final QuotaGuard quotaGuard;
    private final RequestThrottle throttle;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public AmapAroundSource(CrawlProperties props, QuotaGuard quotaGuard, RequestThrottle throttle) {
        this.props = props;
        this.quotaGuard = quotaGuard;
        this.throttle = throttle;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    @Override
    public String name() {
        return "amap-around";
    }

    @Override
    public boolean enabled() {
        return props.isEnabled() && props.getAmap().getWebApiKey() != null
                && !props.getAmap().getWebApiKey().isBlank();
    }

    @Override
    public List<AttractionRaw> fetch(CrawlQuery query, int pageNum) {
        String location = query.location();
        if (location == null && query.city() != null) {
            location = props.getSources().cityCenterMap().get(query.city());
        }
        if (location == null || location.isBlank()) {
            return List.of();
        }
        if (!quotaGuard.tryAcquire()) {
            throw new IllegalStateException("月度配额已用完，停止抓取");
        }
        throttle.waitIfNeeded();
        try {
            String url = props.getAmap().getAroundBaseUrl()
                    + "?key=" + props.getAmap().getWebApiKey()
                    + "&location=" + location
                    + "&radius=" + props.getAmap().getAroundRadius()
                    + "&types=" + props.getAmap().getTypes()
                    + "&page_num=" + pageNum
                    + "&page_size=25"
                    + "&show_fields=photos";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "travel-planner-crawler/1.0")
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return List.of();
            }
            JsonNode root = mapper.readTree(resp.body());
            if (root == null || !"1".equals(root.path("status").asText())) {
                return List.of();
            }
            return parsePois(root, query.city());
        } catch (Exception e) {
            log.warn("[AmapAround] 搜索失败: city={}, error={}", query.city(), e.getMessage());
            return List.of();
        }
    }

    private List<AttractionRaw> parsePois(JsonNode root, String fallbackCity) {
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
            items.add(new AttractionRaw(
                    poi.path("id").asText(null),
                    name, city, poi.path("adname").asText(""),
                    "CULTURE", "", lat, lng, poi.path("address").asText(""),
                    "", null, null, null, null, "[\"景点\"]", "",
                    "", "amap"));
        }
        return items;
    }
}
