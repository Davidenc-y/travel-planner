package com.travel.planning.map.support;

import com.travel.common.dto.ItineraryResponseDTO.DayPlan;
import com.travel.planning.map.model.GeoPoint;
import com.travel.planning.map.model.MapRouteMode;
import org.springframework.stereotype.Component;

/**
 * 行程交通文本/距离 → 路线模式决策（M12-2，策略模式）。
 */
@Component
public class RouteModeResolver {

    public MapRouteMode resolve(DayPlan day, GeoPoint a, GeoPoint b) {
        String text = day == null ? "" : (day.getTransportMode() == null ? "" : day.getTransportMode());
        if (containsAny(text, "自驾", "打车", "包车", "租车", "驾车", "汽车")) {
            return MapRouteMode.DRIVING;
        }
        if (containsAny(text, "步行", "漫步", "观光", "骑行")) {
            return MapRouteMode.WALKING;
        }
        // 公交/地铁等暂不单独接入方向 API：可视化统一走驾车路网，
        // 近距离（≤5km）仍按步行处理，避免“虚线示意”影响地图体验。
        return haversineMeters(a, b) > 5000 ? MapRouteMode.DRIVING : MapRouteMode.WALKING;
    }

    static boolean containsAny(String text, String... keys) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String key : keys) {
            if (text.contains(key)) {
                return true;
            }
        }
        return false;
    }

    public static double haversineMeters(GeoPoint a, GeoPoint b) {
        double r = 6_371_000;
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLng = Math.toRadians(b.lng() - a.lng());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a.lat())) * Math.cos(Math.toRadians(b.lat()))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(h)));
    }
}
