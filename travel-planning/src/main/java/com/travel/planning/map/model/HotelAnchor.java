package com.travel.planning.map.model;

/**
 * 单日住宿锚点（M12）。
 *
 * @param point  可为 null（无坐标时前端仅展示文本）
 * @param source AMAP_GEOCODE=高德地理编码 / LOCAL_CENTER=景点中心回退 /
 *               NO_COORD=无坐标 / NO_TEXT=无住宿文本
 */
public record HotelAnchor(String text, GeoPoint point, String source) {
}
