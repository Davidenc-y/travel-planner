package com.travel.planning.map.amap;

import com.travel.planning.map.model.GeoPoint;
import com.travel.planning.map.model.MapRouteMode;
import com.travel.planning.map.model.RoutePlan;
import com.travel.planning.map.spi.RoutePlannerPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** 高德地图关闭时的空路线规划实现（M12 回滚开关）。 */
@Component
@ConditionalOnProperty(prefix = "travel.map.amap", name = "enabled", havingValue = "false")
public class NoopRoutePlanner implements RoutePlannerPort {

    @Override
    public Optional<RoutePlan> plan(GeoPoint from, GeoPoint to, MapRouteMode mode) {
        return Optional.empty();
    }
}
