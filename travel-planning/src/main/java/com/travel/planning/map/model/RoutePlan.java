package com.travel.planning.map.model;

import java.util.List;

/**
 * 高德方向接口返回的路线结果（M12）。
 *
 * @param distanceMeters  路线距离（米）
 * @param durationSeconds 预计耗时（秒）
 * @param polyline        真实路网折线点（[lng,lat] 序列，可能为空）
 */
public record RoutePlan(
        long distanceMeters,
        long durationSeconds,
        List<GeoPoint> polyline) {
}
