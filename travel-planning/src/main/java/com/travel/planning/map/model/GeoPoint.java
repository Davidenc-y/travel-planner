package com.travel.planning.map.model;

/**
 * 经纬度点（高德 GCJ-02；经度在前、纬度在后，与高德接口/瓦片坐标系一致）。
 */
public record GeoPoint(double lng, double lat) {
}
