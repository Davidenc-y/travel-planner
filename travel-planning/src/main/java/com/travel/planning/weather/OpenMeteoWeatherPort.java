package com.travel.planning.weather;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.common.util.JsonUtils;
import com.travel.planning.map.model.GeoPoint;
import com.travel.planning.map.spi.MapValueCache;
import com.travel.planning.weather.guard.WeatherApiAccessGuard;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * M15-1：Open-Meteo 天气适配器（零 Key）。
 *
 * <p>职责仅 HTTP 传输 + JSON 解析；配额/QPS/熔断由 {@link WeatherApiAccessGuard}
 * 统一治理；城市坐标缓存 30 天、预报结果缓存 24h（经 MapValueCache）。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "travel.weather", name = "enabled", havingValue = "true")
public class OpenMeteoWeatherPort implements WeatherPort {

    private static final String GEO_KEY_PREFIX = "travel:weather:geo:v1:";
    private static final String FORECAST_KEY_PREFIX = "travel:weather:forecast:v1:";

    private final WeatherProperties props;
    private final WeatherApiAccessGuard guard;
    private final MapValueCache cache;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;

    public OpenMeteoWeatherPort(WeatherProperties props, WeatherApiAccessGuard guard,
                                MapValueCache cache) {
        this.props = props;
        this.guard = guard;
        this.cache = cache;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .build();
    }

    @Override
    public Optional<List<DailyWeather>> forecastDates(String city, List<LocalDate> dates) {
        if (city == null || city.isBlank() || dates == null || dates.isEmpty()) {
            return Optional.empty();
        }
        Optional<GeoPoint> point = geocode(city);
        if (point.isEmpty()) {
            return Optional.empty();
        }
        GeoPoint p = point.get();
        LocalDate min = dates.stream().min(LocalDate::compareTo).orElseThrow();
        LocalDate max = dates.stream().max(LocalDate::compareTo).orElseThrow();
        String key = FORECAST_KEY_PREFIX + encode(city) + ":" + min + ":" + max;

        Optional<List<DailyWeather>> cached = readForecastCache(key);
        if (cached.isPresent()) {
            log.info("[Weather] 预报缓存命中: city={}, range={}~{}", city, min, max);
            return Optional.of(filterDates(cached.get(), dates));
        }

        Optional<String> body = guard.execute(() -> httpGet(forecastUrl(p, min, max)));
        if (body.isEmpty()) {
            if (props.isCacheEnabled()) {
                cache.putEmpty(key, Duration.ofHours(1));
            }
            return Optional.empty();
        }
        List<DailyWeather> all = parseForecast(body.get());
        if (all.isEmpty()) {
            if (props.isCacheEnabled()) {
                cache.putEmpty(key, Duration.ofHours(1));
            }
            return Optional.empty();
        }
        if (props.isCacheEnabled()) {
            cache.put(key, JsonUtils.toJson(all), Duration.ofHours(props.getForecastCacheTtlHours()));
        }
        List<DailyWeather> wanted = filterDates(all, dates);
        log.info("[Weather] OK city={} range={}~{} days={} fetched={}",
                city, min, max, wanted.size(), all.size());
        return wanted.isEmpty() ? Optional.empty() : Optional.of(wanted);
    }

    private Optional<GeoPoint> geocode(String city) {
        String key = GEO_KEY_PREFIX + encode(city);
        Optional<String> cached = cache.get(key);
        if (cached.isPresent()) {
            if (cached.get().isBlank()) {
                return Optional.empty();
            }
            try {
                return Optional.ofNullable(JsonUtils.fromJson(cached.get(), GeoPoint.class));
            } catch (Exception e) {
                log.warn("[Weather] 地理缓存解析失败: {}", e.getMessage());
            }
        }
        Optional<String> body = guard.execute(() -> httpGet(geocodeUrl(city)));
        if (body.isEmpty()) {
            if (props.isCacheEnabled()) {
                cache.putEmpty(key, Duration.ofHours(1));
            }
            return Optional.empty();
        }
        Optional<GeoPoint> point = parseGeocoding(body.get());
        if (point.isPresent() && props.isCacheEnabled()) {
            cache.put(key, JsonUtils.toJson(point.get()),
                    Duration.ofDays(props.getGeocodeCacheTtlDays()));
        }
        return point;
    }

    private Optional<List<DailyWeather>> readForecastCache(String key) {
        if (!props.isCacheEnabled()) {
            return Optional.empty();
        }
        Optional<String> cached = cache.get(key);
        if (cached.isEmpty() || cached.get().isBlank()) {
            return Optional.empty();
        }
        try {
            List<DailyWeather> list = JsonUtils.fromJson(
                    cached.get(), new TypeReference<List<DailyWeather>>() {
                    });
            return list == null ? Optional.empty() : Optional.of(list);
        } catch (Exception e) {
            log.warn("[Weather] 预报缓存解析失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> httpGet(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(props.getTimeoutMs()))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("[Weather] HTTP {}: url={}", resp.statusCode(), url);
                return Optional.empty();
            }
            return Optional.of(resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("[Weather] 请求失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String geocodeUrl(String city) {
        return props.getGeocodingBaseUrl() + "/search?name=" + encode(city)
                + "&count=1&language=zh&format=json";
    }

    private String forecastUrl(GeoPoint p, LocalDate start, LocalDate end) {
        return props.getForecastBaseUrl() + "/forecast?latitude="
                + String.format(Locale.ROOT, "%.5f", p.lat())
                + "&longitude=" + String.format(Locale.ROOT, "%.5f", p.lng())
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                + "&start_date=" + start + "&end_date=" + end
                + "&timezone=auto";
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** 解析 Open-Meteo geocoding 响应（package-private 供契约测试）。 */
    static Optional<GeoPoint> parseGeocoding(String body) {
        try {
            JsonNode root = new ObjectMapper().readTree(body);
            JsonNode first = root.path("results").path(0);
            if (first.isMissingNode() || first.isNull()) {
                return Optional.empty();
            }
            return Optional.of(new GeoPoint(
                    first.path("longitude").asDouble(),
                    first.path("latitude").asDouble()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 解析 Open-Meteo forecast daily 响应（package-private 供契约测试）。 */
    static List<DailyWeather> parseForecast(String body) {
        try {
            JsonNode daily = new ObjectMapper().readTree(body).path("daily");
            JsonNode times = daily.path("time");
            JsonNode codes = daily.path("weather_code");
            JsonNode maxs = daily.path("temperature_2m_max");
            JsonNode mins = daily.path("temperature_2m_min");
            int n = Math.min(times.size(), Math.min(codes.size(),
                    Math.min(maxs.size(), mins.size())));
            List<DailyWeather> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int code = codes.get(i).asInt();
                out.add(new DailyWeather(
                        times.get(i).asText(),
                        code,
                        wmoText(code),
                        maxs.get(i).isNull() ? null : maxs.get(i).asDouble(),
                        mins.get(i).isNull() ? null : mins.get(i).asDouble()));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<DailyWeather> filterDates(List<DailyWeather> all, List<LocalDate> dates) {
        Set<String> wanted = new HashSet<>();
        for (LocalDate d : dates) {
            wanted.add(d.toString());
        }
        return all.stream().filter(w -> wanted.contains(w.date())).toList();
    }

    /** WMO weather code → 中文文案（子集覆盖常用代码）。 */
    static String wmoText(int code) {
        if (code == 0) {
            return "晴";
        }
        if (code == 1 || code == 2) {
            return code == 1 ? "大部晴朗" : "多云";
        }
        if (code == 3) {
            return "阴";
        }
        if (code == 45 || code == 48) {
            return "雾";
        }
        if (code >= 51 && code <= 57) {
            return "毛毛雨";
        }
        if (code >= 61 && code <= 67) {
            return "雨";
        }
        if (code >= 71 && code <= 77) {
            return "雪";
        }
        if (code >= 80 && code <= 82) {
            return "阵雨";
        }
        if (code == 85 || code == 86) {
            return "阵雪";
        }
        if (code >= 95 && code <= 99) {
            return "雷阵雨";
        }
        return "其他";
    }
}
