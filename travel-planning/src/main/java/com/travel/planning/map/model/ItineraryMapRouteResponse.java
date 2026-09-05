package com.travel.planning.map.model;

import com.travel.planning.weather.DailyWeather;

import java.util.List;
import java.util.Map;

/**
 * 行程地图路线响应（M12）。
 *
 * @param status CACHED=全部缓存 / FULL=全部成功 / PARTIAL=超长截断 /
 *               DEGRADED=存在降级段
 * @param usage  hits/routeCalls/geocodeCalls 观测计数
 */
public record ItineraryMapRouteResponse(
        Long itineraryId,
        String destination,
        String status,
        List<DayMapRoute> days,
        Map<String, Integer> usage,
        List<DailyWeather> weather) {
}
