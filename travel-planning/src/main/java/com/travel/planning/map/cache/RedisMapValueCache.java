package com.travel.planning.map.cache;

import com.travel.planning.map.spi.MapValueCache;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis 地图结果缓存（M12-2）：路线 JSON / 地理编码 JSON / 空结果标记。
 */
@RequiredArgsConstructor
@Component
@ConditionalOnProperty(prefix = "travel.map.amap", name = "cache-enabled",
        havingValue = "true", matchIfMissing = true)
public class RedisMapValueCache implements MapValueCache {

    private final StringRedisTemplate redisTemplate;

    @Override
    public Optional<String> get(String key) {
        try {
            return Optional.ofNullable(redisTemplate.opsForValue().get(key));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, value, ttl);
        } catch (Exception e) {
            // 缓存失败不阻断主流程（下次再查外部）
        }
    }

    @Override
    public void putEmpty(String key, Duration ttl) {
        put(key, "", ttl);
    }
}
