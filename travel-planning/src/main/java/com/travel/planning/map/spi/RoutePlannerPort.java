package com.travel.planning.map.spi;

import com.travel.planning.map.model.GeoPoint;
import com.travel.planning.map.model.MapRouteMode;
import com.travel.planning.map.model.RoutePlan;

import java.util.Optional;

/**
 * 路线规划端口（M12）：消费方只依赖接口，高德/模拟实现可替换。
 */
public interface RoutePlannerPort {

    /** 两点间路线；失败/无结果返回 empty（由上层降级，不抛异常）。 */
    Optional<RoutePlan> plan(GeoPoint from, GeoPoint to, MapRouteMode mode);
}
