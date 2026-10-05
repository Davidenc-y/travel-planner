package com.travel.common.cache;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * BB-4：通用 Redis 查询结果缓存（StringRedisTemplate + JSON 序列化）。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>fail-open</b>：Redis 异常（get/put 均含）→ 降级直查 supplier（P0-BB⑴）；</li>
 *   <li><b>TTL 随机化</b>：put 时 TtlJitter.jitter(base) 加 ±20% 抖动（P0-BB⑵）；</li>
 *   <li><b>空值缓存</b>：supplier 返回 null → 存空标记 "{EMPTY}"（短 TTL 60s），下次命中跳过 DB（P0-BB⑶）；</li>
 *   <li><b>线程安全</b>：无进程内状态，全部操作委托 StringRedisTemplate；多实例安全（Redis 为共享层）。</li>
 * </ul>
 *
 * <p>注意：不做互斥重建（击穿防护）——当前量级（看板单管理员 + 城市列表日变）不值得引入
 * 分布式锁复杂度；如未来高并发场景需要，在此类内加 SETNX singleflight 即可（接口不变）。</p>
 */
@Slf4j
public class RedisResultCache {

    private static final String EMPTY_MARKER = "{EMPTY}";
    private static final Duration EMPTY_TTL = Duration.ofSeconds(60);
    /** BB 审计直修：注册 JavaTimeModule——selectTopSlowTurns 等返回含 LocalDateTime 的 Map，
     * 默认 ObjectMapper 不支持 Java 8 日期类型（writeValueAsString 抛异常被 fail-open 吞掉，
     * 键静默不写入=turn-latency 缓存从未生效的根因）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    private final StringRedisTemplate redis;
    private final String keyPrefix;

    public RedisResultCache(StringRedisTemplate redis, String keyPrefix) {
        this.redis = redis;
        this.keyPrefix = keyPrefix;
    }

    /** 读取缓存——命中返回反序列化值（含空标记→null），miss 或异常返回 null（调用方自行直查）。 */
    public <T> T get(String key, Class<T> type) {
        try {
            String json = redis.opsForValue().get(keyPrefix + key);
            if (json == null) {
                return null;
            }
            if (EMPTY_MARKER.equals(json)) {
                return null;
            }
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            log.debug("[RedisResultCache] get 降级直查: key={}, err={}", keyPrefix + key, e.getMessage());
            return null;
        }
    }

    /** 写入缓存——TTL 经 TtlJitter 随机化；null 值存空标记（P0-BB⑶）。 */
    public <T> void put(String key, T value, Duration baseTtl) {
        try {
            String fullKey = keyPrefix + key;
            Duration ttl = TtlJitter.jitter(baseTtl);
            if (value == null) {
                redis.opsForValue().set(fullKey, EMPTY_MARKER, EMPTY_TTL);
            } else {
                redis.opsForValue().set(fullKey, MAPPER.writeValueAsString(value), ttl);
            }
        } catch (Exception e) {
            log.debug("[RedisResultCache] put 降级跳过: key={}, err={}", keyPrefix + key, e.getMessage());
        }
    }

    /** 组合读：缓存命中直接返回，miss→supplier 直查→写缓存→返回值。 */
    public <T> T computeIfAbsent(String key, Class<T> type, Duration ttl, Supplier<T> supplier) {
        T cached = get(key, type);
        if (cached != null || isCachedEmpty(key)) {
            return cached;
        }
        T value = supplier.get();
        put(key, value, ttl);
        return value;
    }

    /** 检查是否命中空标记（防穿透第二次查询跳过 DB）。 */
    public boolean isCachedEmpty(String key) {
        try {
            return EMPTY_MARKER.equals(redis.opsForValue().get(keyPrefix + key));
        } catch (Exception e) {
            return false;
        }
    }

    /** 主动失效（写→删缓存链）。 */
    public void evict(String key) {
        try {
            redis.delete(keyPrefix + key);
        } catch (Exception e) {
            log.debug("[RedisResultCache] evict 降级跳过: key={}, err={}", keyPrefix + key, e.getMessage());
        }
    }
}
