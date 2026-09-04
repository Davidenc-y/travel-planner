package com.travel.planning.map.model;

import java.util.List;

/**
 * 行程中相邻两个景点的路线段（M12）。
 *
 * @param source CACHE=缓存命中 / AMAP=真实调用成功 / LOCAL_FALLBACK=示意降级 /
 *               MISSING=缺少坐标 / UNSUPPORTED_MODE=公交等暂不支持
 */
public record MapRouteSegment(
        String fromName,
        double fromLng,
        double fromLat,
        String toName,
        double toLng,
        double toLat,
        MapRouteMode mode,
        Long distanceMeters,
        Long durationSeconds,
        List<GeoPoint> polyline,
        String source) {
}
