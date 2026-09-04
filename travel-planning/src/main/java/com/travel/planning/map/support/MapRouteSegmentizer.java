package com.travel.planning.map.support;

import com.travel.common.dto.ItineraryResponseDTO.AttractionVisit;
import com.travel.common.dto.ItineraryResponseDTO.DayPlan;
import com.travel.planning.map.model.GeoPoint;
import com.travel.planning.map.model.MapRouteMode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 每日行程 → 相邻景点路线段切分（M12-2，纯函数）。
 *
 * <p>只对两个都有坐标的相邻景点生成 Leg；缺失坐标的景点由前端在
 * dayPlans 点位列表中展示，不进路线调用。</p>
 */
@Component
public class MapRouteSegmentizer {

    /** 一条待规划的路线腿（尚无外部结果）。 */
    public record Leg(String fromName, GeoPoint from,
                      String toName, GeoPoint to,
                      MapRouteMode mode) {
    }

    private final RouteModeResolver modeResolver;

    public MapRouteSegmentizer(RouteModeResolver modeResolver) {
        this.modeResolver = modeResolver;
    }

    public List<Leg> dayLegs(DayPlan day) {
        List<Leg> result = new ArrayList<>();
        if (day == null || day.getAttractions() == null) {
            return result;
        }
        AttractionVisit prev = null;
        for (AttractionVisit v : day.getAttractions()) {
            if (v == null || v.getName() == null || v.getName().isBlank()) {
                continue;
            }
            GeoPoint p = pointOf(v);
            if (p == null) {
                continue; // 无坐标景点不打断连线：前一可用景点与后一可用景点直连
            }
            if (prev != null) {
                GeoPoint prevPoint = pointOf(prev);
                if (prevPoint != null) {
                    result.add(new Leg(prev.getName(), prevPoint, v.getName(), p,
                            modeResolver.resolve(day, prevPoint, p)));
                }
            }
            prev = v;
        }
        return result;
    }

    public static GeoPoint pointOf(AttractionVisit v) {
        if (v == null || v.getLatitude() == null || v.getLongitude() == null) {
            return null;
        }
        return new GeoPoint(v.getLongitude(), v.getLatitude());
    }
}
