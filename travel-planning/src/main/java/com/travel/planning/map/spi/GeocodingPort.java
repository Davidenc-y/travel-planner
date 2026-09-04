package com.travel.planning.map.spi;

import com.travel.planning.map.model.GeoPoint;

import java.util.Optional;

/**
 * 地理编码端口（M12）：住宿文本 → 坐标。
 */
public interface GeocodingPort {

    Optional<GeoPoint> geocode(String address, String city);
}
