package com.travel.planning.map.support;

import com.travel.common.dto.ItineraryResponseDTO.DayPlan;
import com.travel.common.util.JsonUtils;
import com.travel.planning.map.amap.AmapMapProperties;
import com.travel.planning.map.guard.MapApiAccessGuard;
import com.travel.planning.map.model.GeoPoint;
import com.travel.planning.map.model.HotelAnchor;
import com.travel.planning.map.spi.GeocodingPort;
import com.travel.planning.map.spi.MapValueCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 住宿文本锚点解析（M12-2）：
 * 缓存命中 → 高德地理编码（经 {@link MapApiAccessGuard} 治理）→ 景点中心回退。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HotelAnchorResolver {

    private static final String GEO_KEY_PREFIX = "travel:map:geocode:v1:";
    private static final String GEO_EMPTY_PREFIX = "travel:map:geocode:empty:v1:";

    private final GeocodingPort geocodingPort;
    private final MapValueCache cache;
    private final MapApiAccessGuard guard;
    private final AmapMapProperties props;

    public HotelAnchor resolve(DayPlan day, String city, List<GeoPoint> dayPoints) {
        String text = day == null ? null : day.getHotelSuggestion();
        if (text == null || text.isBlank()) {
            return new HotelAnchor("", null, "NO_TEXT");
        }
        String normalized = text.trim();
        String safeCity = city == null ? "" : city.trim();
        String hash = sha256Hex(normalized);
        String key = GEO_KEY_PREFIX + safeCity + ":" + hash;
        String emptyKey = GEO_EMPTY_PREFIX + safeCity + ":" + hash;

        if (props.isCacheEnabled()) {
            Optional<String> hit = cache.get(key);
            if (hit.isPresent() && !hit.get().isEmpty()) {
                try {
                    GeoPoint point = JsonUtils.fromJson(hit.get(), GeoPoint.class);
                    if (point != null) {
                        return new HotelAnchor(text, point, "AMAP_GEOCODE");
                    }
                } catch (Exception e) {
                    log.warn("[HotelAnchor] 缓存解析失败: {}", e.getMessage());
                }
            }
            if (cache.get(emptyKey).isPresent()) {
                return fallback(text, dayPoints);
            }
        }

        Optional<GeoPoint> point = guard.execute(
                "geocode", () -> geocodingPort.geocode(normalized, safeCity));
        if (point.isPresent()) {
            if (props.isCacheEnabled()) {
                cache.put(key, JsonUtils.toJson(point.get()),
                        Duration.ofDays(props.getGeocodeCacheTtlDays()));
            }
            return new HotelAnchor(text, point.get(), "AMAP_GEOCODE");
        }
        if (props.isCacheEnabled()) {
            cache.putEmpty(emptyKey, Duration.ofDays(1));
        }
        return fallback(text, dayPoints);
    }

    private HotelAnchor fallback(String text, List<GeoPoint> dayPoints) {
        if (dayPoints == null || dayPoints.isEmpty()) {
            return new HotelAnchor(text, null, "NO_COORD");
        }
        double lng = dayPoints.stream().mapToDouble(GeoPoint::lng).average().orElse(0);
        double lat = dayPoints.stream().mapToDouble(GeoPoint::lat).average().orElse(0);
        return new HotelAnchor(text, new GeoPoint(lng, lat), "LOCAL_CENTER");
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            // SHA-256 在 JDK 必然存在；极端失败退化为输入本身（仅影响 key 长度）
            return Integer.toHexString(input.hashCode());
        }
    }
}
