package com.travel.planning.map.model;

import java.util.List;

/** 单日地图路线（M12）。 */
public record DayMapRoute(
        int day,
        List<MapRouteSegment> segments,
        HotelAnchor hotel) {
}
