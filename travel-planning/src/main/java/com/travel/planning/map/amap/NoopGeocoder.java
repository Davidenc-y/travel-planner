package com.travel.planning.map.amap;

import com.travel.planning.map.model.GeoPoint;
import com.travel.planning.map.spi.GeocodingPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** 高德地图关闭时的空地理编码实现（M12 回滚开关）。 */
@Component
@ConditionalOnProperty(prefix = "travel.map.amap", name = "enabled", havingValue = "false")
public class NoopGeocoder implements GeocodingPort {

    @Override
    public Optional<GeoPoint> geocode(String address, String city) {
        return Optional.empty();
    }
}
