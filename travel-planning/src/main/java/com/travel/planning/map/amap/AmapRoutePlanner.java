package com.travel.planning.map.amap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.planning.map.model.GeoPoint;
import com.travel.planning.map.model.MapRouteMode;
import com.travel.planning.map.model.RoutePlan;
import com.travel.planning.map.spi.RoutePlannerPort;
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
import java.util.Locale;
import java.util.Optional;

/**
 * 高德 Web 服务方向 API（v3）路线规划适配器（M12-1）。
 *
 * <p>只负责 HTTP 传输与 JSON 解析；配额/限频/熔断由 {@code MapApiAccessGuard} 统一治理，
 * 使本类可被 Mock/未来 v5 实现替换。</p>
 *
 * <p>坐标解析：v3 返回 route.paths[0].steps[].polyline，形如
 * “lng,lat;lng,lat;…”；驾车需 extensions=all 才会返回完整 steps。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "travel.map.amap", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class AmapRoutePlanner implements RoutePlannerPort {

    private final AmapMapProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;

    public AmapRoutePlanner(AmapMapProperties props) {
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .build();
    }

    @Override
    public Optional<RoutePlan> plan(GeoPoint from, GeoPoint to, MapRouteMode mode) {
        if (mode == MapRouteMode.UNSUPPORTED || !props.keyConfigured()) {
            return Optional.empty();
        }
        long startedAt = System.currentTimeMillis();
        int attempts = 0;
        while (true) {
            try {
                String url = buildUrl(from, to, mode);
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMillis(props.getTimeoutMs()))
                        .GET()
                        .build();
                HttpResponse<String> resp = httpClient.send(
                        req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    if (retry(attempts)) {
                        attempts++;
                        continue;
                    }
                    log.warn("[AmapRoute] HTTP {}: {}→{}", resp.statusCode(), from, to);
                    return Optional.empty();
                }
                Optional<RoutePlan> parsed = parseRoute(mapper, resp.body());
                if (parsed.isPresent()) {
                    RoutePlan plan = parsed.get();
                    log.info("[AmapRoute] OK mode={} from={} to={} distanceM={} durationS={} points={} elapsedMs={}",
                            mode, coord(from), coord(to),
                            plan.distanceMeters(), plan.durationSeconds(),
                            plan.polyline().size(), System.currentTimeMillis() - startedAt);
                    return parsed;
                }
                if (retry(attempts)) {
                    attempts++;
                    continue;
                }
                return Optional.empty();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (Exception e) {
                if (retry(attempts)) {
                    attempts++;
                    continue;
                }
                log.warn("[AmapRoute] 路线规划失败: {}→{} error={}", from, to, e.getMessage());
                return Optional.empty();
            }
        }
    }

    private boolean retry(int attempts) {
        if (attempts >= props.getMaxRetries()) {
            return false;
        }
        try {
            Thread.sleep(props.getRetryBackoffMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return true;
    }

    String buildUrl(GeoPoint from, GeoPoint to, MapRouteMode mode) {
        String path = mode == MapRouteMode.DRIVING ? "/driving" : "/walking";
        StringBuilder sb = new StringBuilder(props.getRouteBaseUrl())
                .append(path)
                .append("?origin=").append(coord(from))
                .append("&destination=").append(coord(to));
        if (mode == MapRouteMode.DRIVING) {
            sb.append("&extensions=all&strategy=0");
        }
        return sb.append("&key=").append(props.getWebApiKey()).toString();
    }

    static String coord(GeoPoint p) {
        return String.format(Locale.ROOT, "%.6f,%.6f", p.lng(), p.lat());
    }

    /** 解析 v3 方向响应（package-private 供离线单测）。 */
    static Optional<RoutePlan> parseRoute(ObjectMapper mapper, String body) {
        try {
            JsonNode root = mapper.readTree(body);
            if (root == null || !"1".equals(root.path("status").asText())) {
                return Optional.empty();
            }
            JsonNode path = root.path("route").path("paths").path(0);
            if (path.isMissingNode() || path.isNull()) {
                return Optional.empty();
            }
            long distance = path.path("distance").asLong(0);
            long duration = path.path("duration").asLong(0);
            List<GeoPoint> points = new ArrayList<>();
            JsonNode steps = path.path("steps");
            if (steps.isArray()) {
                for (JsonNode step : steps) {
                    appendPolyline(step.path("polyline").asText(""), points);
                }
            }
            return points.isEmpty() ? Optional.empty()
                    : Optional.of(new RoutePlan(distance, duration, points));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static void appendPolyline(String polyline, List<GeoPoint> out) {
        if (polyline == null || polyline.isBlank()) {
            return;
        }
        for (String pair : polyline.split(";")) {
            String[] xy = pair.split(",", -1);
            if (xy.length != 2) {
                continue;
            }
            try {
                GeoPoint p = new GeoPoint(
                        Double.parseDouble(xy[0].trim()),
                        Double.parseDouble(xy[1].trim()));
                // 高德相邻 step 首尾点重复，合并连续重复点避免折线抖动
                if (out.isEmpty() || !out.get(out.size() - 1).equals(p)) {
                    out.add(p);
                }
            } catch (NumberFormatException ignored) {
                // 单点脏数据跳过，不中断整段
            }
        }
    }
}
