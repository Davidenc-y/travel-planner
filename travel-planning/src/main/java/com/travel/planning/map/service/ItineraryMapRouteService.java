package com.travel.planning.map.service;

import com.travel.common.dto.ItineraryResponseDTO;
import com.travel.common.dto.ItineraryResponseDTO.AttractionVisit;
import com.travel.common.dto.ItineraryResponseDTO.DayPlan;
import com.travel.common.util.JsonUtils;
import com.travel.planning.map.amap.AmapMapProperties;
import com.travel.planning.map.guard.MapApiAccessGuard;
import com.travel.planning.map.model.DayMapRoute;
import com.travel.planning.map.model.GeoPoint;
import com.travel.planning.map.model.HotelAnchor;
import com.travel.planning.map.model.ItineraryMapRouteResponse;
import com.travel.planning.map.model.MapRouteMode;
import com.travel.planning.map.model.MapRouteSegment;
import com.travel.planning.map.model.RoutePlan;
import com.travel.planning.map.spi.MapValueCache;
import com.travel.planning.map.spi.RoutePlannerPort;
import com.travel.planning.map.support.HotelAnchorResolver;
import com.travel.planning.map.support.MapRouteSegmentizer;
import com.travel.planning.map.support.MapRouteSegmentizer.Leg;
import com.travel.planning.map.support.RouteModeResolver;
import com.travel.planning.service.ItineraryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 行程地图路线编排（M12-2）：
 * 复用 M11-2 坐标装饰后的详情 DTO → 切分点对 → 缓存/配额/真实调用 → 响应组装。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItineraryMapRouteService {

    private static final String ROUTE_KEY_PREFIX = "travel:map:route:v1:";

    private final ItineraryService itineraryService;
    private final MapRouteSegmentizer segmentizer;
    private final RoutePlannerPort routePlanner;
    private final HotelAnchorResolver hotelAnchorResolver;
    private final MapValueCache cache;
    private final MapApiAccessGuard guard;
    private final AmapMapProperties props;

    public ItineraryMapRouteResponse mapRoutes(Long itineraryId) {
        ItineraryResponseDTO dto = itineraryService.getById(itineraryId);
        int routeBefore = guard.attemptsOf("route");
        int geocodeBefore = guard.attemptsOf("geocode");

        List<DayMapRoute> days = new ArrayList<>();
        boolean anyAmap = false;
        boolean anyCache = false;
        boolean degraded = false;
        boolean partial = false;
        int hits = 0;
        int processed = 0;
        int maxSegments = Math.max(1, props.getMaxSegmentsPerRequest());

        if (dto != null && dto.getDayPlans() != null) {
            for (DayPlan day : dto.getDayPlans()) {
                List<Leg> legs = segmentizer.dayLegs(day);
                List<GeoPoint> dayPoints = dayPoints(day);
                List<MapRouteSegment> segments = new ArrayList<>();
                for (Leg leg : legs) {
                    if (processed >= maxSegments) {
                        partial = true;
                        segments.add(fallbackSegment(leg, "LOCAL_FALLBACK"));
                        continue;
                    }
                    processed++;
                    if (leg.mode() == MapRouteMode.UNSUPPORTED) {
                        segments.add(fallbackSegment(leg, "UNSUPPORTED_MODE"));
                        continue;
                    }
                    String key = routeKey(leg);
                    Optional<RoutePlan> cached = readRouteCache(key);
                    if (cached.isPresent()) {
                        anyCache = true;
                        hits++;
                        segments.add(toSegment(leg, cached.get(), "CACHE"));
                        continue;
                    }
                    Optional<RoutePlan> plan = guard.execute(
                            "route", () -> routePlanner.plan(leg.from(), leg.to(), leg.mode()));
                    if (plan.isPresent()) {
                        anyAmap = true;
                        if (props.isCacheEnabled()) {
                            cache.put(key, JsonUtils.toJson(plan.get()),
                                    Duration.ofDays(props.getRouteCacheTtlDays()));
                        }
                        segments.add(toSegment(leg, plan.get(), "AMAP"));
                    } else {
                        degraded = true;
                        segments.add(fallbackSegment(leg, "LOCAL_FALLBACK"));
                    }
                }
                HotelAnchor hotel = hotelAnchorResolver.resolve(
                        day, dto.getDestination(), dayPoints);
                days.add(new DayMapRoute(
                        day.getDay() == null ? days.size() + 1 : day.getDay(),
                        segments,
                        hotel));
            }
        }

        int routeCalls = guard.attemptsOf("route") - routeBefore;
        int geocodeCalls = guard.attemptsOf("geocode") - geocodeBefore;
        Map<String, Integer> usage = new HashMap<>();
        usage.put("hits", hits);
        usage.put("routeCalls", routeCalls);
        usage.put("geocodeCalls", geocodeCalls);

        String status = statusOf(anyAmap, anyCache, degraded, partial);
        int totalSegments = days.stream().mapToInt(d -> d.segments().size()).sum();
        log.info("[MapRoute] itineraryId={} destination={} status={} days={} segments={} "
                        + "hits={} routeCalls={} geocodeCalls={}",
                dto == null ? null : dto.getId(),
                dto == null ? "" : dto.getDestination(),
                status, days.size(), totalSegments,
                hits, routeCalls, geocodeCalls);
        return new ItineraryMapRouteResponse(
                dto == null ? null : dto.getId(),
                dto == null ? "" : dto.getDestination(),
                status,
                days,
                usage);
    }

    private Optional<RoutePlan> readRouteCache(String key) {
        if (!props.isCacheEnabled()) {
            return Optional.empty();
        }
        Optional<String> value = cache.get(key);
        if (value.isEmpty() || value.get().isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(JsonUtils.fromJson(value.get(), RoutePlan.class));
        } catch (Exception e) {
            log.warn("[MapRoute] 路线缓存解析失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private MapRouteSegment toSegment(Leg leg, RoutePlan plan, String source) {
        return new MapRouteSegment(
                leg.fromName(), leg.from().lng(), leg.from().lat(),
                leg.toName(), leg.to().lng(), leg.to().lat(),
                leg.mode(),
                plan.distanceMeters(),
                plan.durationSeconds(),
                plan.polyline(),
                source);
    }

    private MapRouteSegment fallbackSegment(Leg leg, String source) {
        long meters = (long) Math.round(RouteModeResolver.haversineMeters(leg.from(), leg.to()));
        return new MapRouteSegment(
                leg.fromName(), leg.from().lng(), leg.from().lat(),
                leg.toName(), leg.to().lng(), leg.to().lat(),
                leg.mode(),
                meters,
                null,
                null,
                source);
    }

    private String routeKey(Leg leg) {
        return ROUTE_KEY_PREFIX + leg.mode() + ":"
                + coordKey(leg.from()) + ":" + coordKey(leg.to());
    }

    private String coordKey(GeoPoint p) {
        return String.format(Locale.ROOT, "%.5f,%.5f", p.lng(), p.lat());
    }

    private List<GeoPoint> dayPoints(DayPlan day) {
        List<GeoPoint> points = new ArrayList<>();
        if (day == null || day.getAttractions() == null) {
            return points;
        }
        for (AttractionVisit v : day.getAttractions()) {
            GeoPoint p = MapRouteSegmentizer.pointOf(v);
            if (p != null) {
                points.add(p);
            }
        }
        return points;
    }

    private String statusOf(boolean anyAmap, boolean anyCache, boolean degraded, boolean partial) {
        if (degraded) {
            return "DEGRADED";
        }
        if (partial) {
            return "PARTIAL";
        }
        if (anyAmap) {
            return "FULL";
        }
        if (anyCache) {
            return "CACHED";
        }
        return "EMPTY";
    }
}
