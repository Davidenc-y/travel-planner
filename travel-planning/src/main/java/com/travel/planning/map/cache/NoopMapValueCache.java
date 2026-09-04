package com.travel.planning.map.cache;

import com.travel.planning.map.spi.MapValueCache;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/** 缓存关闭时的空实现（M12-2）。 */
@Component
@ConditionalOnProperty(prefix = "travel.map.amap", name = "cache-enabled",
        havingValue = "false")
public class NoopMapValueCache implements MapValueCache {

    @Override
    public Optional<String> get(String key) {
        return Optional.empty();
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        // no-op
    }

    @Override
    public void putEmpty(String key, Duration ttl) {
        // no-op
    }
}
