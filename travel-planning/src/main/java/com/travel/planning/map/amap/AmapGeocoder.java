package com.travel.planning.map.amap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.planning.map.model.GeoPoint;
import com.travel.planning.map.spi.GeocodingPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * 高德 v3 地理编码适配器（M12-1）：住宿文本 → 坐标。
 *
 * <p>只负责 HTTP 传输与解析，配额/限频/熔断由 {@code MapApiAccessGuard} 统一治理。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "travel.map.amap", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class AmapGeocoder implements GeocodingPort {

    private final AmapMapProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;

    public AmapGeocoder(AmapMapProperties props) {
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .build();
    }

    @Override
    public Optional<GeoPoint> geocode(String address, String city) {
        if (!props.keyConfigured()) {
            return Optional.empty();
        }
        long startedAt = System.currentTimeMillis();
        try {
            String encoded = URLEncoder.encode(address, StandardCharsets.UTF_8);
            String url = props.getGeocodeBaseUrl()
                    + "?address=" + encoded
                    + "&city=" + URLEncoder.encode(city == null ? "" : city, StandardCharsets.UTF_8)
                    + "&key=" + props.getWebApiKey();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(props.getTimeoutMs()))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("[AmapGeocode] HTTP {}: address={}", resp.statusCode(), address);
                return Optional.empty();
            }
            Optional<GeoPoint> point = parseGeocode(mapper, resp.body());
            if (point.isPresent()) {
                log.info("[AmapGeocode] OK city={} address={} location={},{} elapsedMs={}",
                        city, address, point.get().lng(), point.get().lat(),
                        System.currentTimeMillis() - startedAt);
            } else {
                log.warn("[AmapGeocode] 无结果: city={} address={}", city, address);
            }
            return point;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("[AmapGeocode] 地理编码失败: address={} error={}", address, e.getMessage());
            return Optional.empty();
        }
    }

    /** 解析 v3 地理编码响应（package-private 供离线单测）。 */
    static Optional<GeoPoint> parseGeocode(ObjectMapper mapper, String body) {
        try {
            JsonNode root = mapper.readTree(body);
            if (root == null || !"1".equals(root.path("status").asText())) {
                return Optional.empty();
            }
            JsonNode geo = root.path("geocodes").path(0);
            String location = geo.path("location").asText("");
            if (location.isBlank()) {
                return Optional.empty();
            }
            String[] xy = location.split(",", -1);
            if (xy.length != 2) {
                return Optional.empty();
            }
            return Optional.of(new GeoPoint(
                    Double.parseDouble(xy[0].trim()),
                    Double.parseDouble(xy[1].trim())));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
