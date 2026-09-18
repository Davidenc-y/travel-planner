package com.travel.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MI-6：灰度开关单点（只读视图，读 Environment；不含动态写——动态切换留人工批次）。
 *
 * <p>聚合现存灰度键全集（前置核验 2026-09-13：{@code gray.stream.webflux-enabled}、
 * {@code gray.brief.redis.enabled}；2026-09-14 D-2a 增 {@code gray.itinerary-detail-cache.enabled}、
 * D-2b 增 {@code gray.writeback-consumer.enabled}；
 * 新增灰度键时在此登记 KNOWN_KEYS 即可进快照）。
 * {@code enabled(key)} 供消费点做字面判断（当前代码内零字面判断消费点——两键均为
 * 注解/条件装配驱动，消费点替换记零处）；{@code snapshot()} 输出全部已知键当前值，
 * 供 {@code gray.release.snapshot} 端点（AdminReliabilityController）只读输出。</p>
 *
 * <p>MM-8：内存覆盖层（动态写能力）——{@code overrides} 仅在内存，重启即失效；
 * {@code enabled(key)} 先查覆盖层再落 Environment（覆盖层为空时行为与 MI-6 逐字节
 * 一致）；{@code override/clearOverride/clearAllOverrides} 受
 * {@code travel.gray.dynamic-write.enabled} 门控（默认 false=false 时全部 no-op，
 * E-19④：代码可产出、启用须人工改配置）；变更审计日志前缀 {@code [GrayOverride]}。</p>
 *
 * <p>D-2c 边界三句：本能力仅在启用它的进程内生效（当前=8081，local profile 配置）；
 * 内存覆盖层重启即失，持久化不存在；8083 进程（{@code gray.brief.redis.enabled} 所在）
 * 不可达本覆盖层，其灰度键取值不因本开关变化。</p>
 *
 * <p>E-4a（20260918，E-32 参数冻结）：覆盖层升级 Redis hash {@code travel:gray:overrides}
 * （field=灰度键，value="true"/"false"）——本地 ConcurrentHashMap 缓存 TTL 5000ms
 * （System.nanoTime 时间戳）：读=TTL 内走缓存→过期 HGET 回源；写=HSET+本地失效。
 * Redis 异常 fail-open 回落 Environment（WARN 节流：每键每 60s 一条）；
 * {@code travel.gray.dynamic-write.enabled} 开关 false 时写路径全部 no-op 语义不变
 * （读路径与既有覆盖层语义一致：存在覆盖即优先）。StringRedisTemplate 经
 * ObjectProvider 可选注入：无 Redis 环境（缺依赖/缺连接工厂）自动回落纯 Environment
 * 语义；单参构造保留供既有单测与无 Redis 装配。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT */
@Slf4j
@Component
public class GrayReleaseManager {

    /** 现存灰度键全集（新增灰度键在此登记即进快照）。 */
    public static final List<String> KNOWN_KEYS = List.of(
            "gray.stream.webflux-enabled",
            "gray.brief.redis.enabled",
            "gray.itinerary-detail-cache.enabled",
            "gray.writeback-consumer.enabled");

    /** E-32 冻结：覆盖层 Redis hash 键。 */
    static final String OVERRIDE_HASH_KEY = "travel:gray:overrides";

    /** E-32 冻结：本地缓存 TTL=5000ms（nanoTime 口径）。 */
    static final long CACHE_TTL_NANOS = 5_000_000_000L;

    /** E-32 冻结：Redis 异常 WARN 节流窗口=每键每 60s 一条。 */
    static final long WARN_THROTTLE_NANOS = 60_000_000_000L;

    private final Environment environment;

    private final ObjectProvider<StringRedisTemplate> redisProvider;

    /** MM-8：动态写内存覆盖层（key → 覆盖值；仅内存，重启即失效）。 */
    private final ConcurrentHashMap<String, Boolean> overrides = new ConcurrentHashMap<>();

    /** E-4a：本地 TTL 缓存（key → 值+装载时间戳；TTL 内直接命中，过期 HGET 回源）。 */
    private final ConcurrentHashMap<String, CachedOverride> cache = new ConcurrentHashMap<>();

    /** E-4a：Redis 异常 WARN 节流时间戳（key → 上次告警 nanoTime）。 */
    private final ConcurrentHashMap<String, Long> lastWarnAt = new ConcurrentHashMap<>();

    /** MM-8：动态写能力开关（默认 false；置 true 属 E-19④ 人工闸门决策，执行器不得代行）。 */
    @Value("${travel.gray.dynamic-write.enabled:false}")
    private boolean dynamicWriteEnabled;

    /** 兼容构造：无 Redis 装配/既有单测（纯 Environment 语义，覆盖层仅本地内存）。 */
    public GrayReleaseManager(Environment environment) {
        this(environment, null);
    }

    /** E-4a：完整构造（ObjectProvider 可选注入——无 Redis bean 时 getIfAvailable()=null）。 */
    @Autowired
    public GrayReleaseManager(Environment environment,
                              ObjectProvider<StringRedisTemplate> redisProvider) {
        this.environment = environment;
        this.redisProvider = redisProvider;
    }

    /** 覆盖层当前值：TTL 内走缓存→过期/未命中 HGET 回源；无 Redis 或 Redis 异常返回 null（回落 Environment）。 */
    private Boolean readOverride(String key) {
        Boolean legacy = overrides.get(key);
        if (legacy != null) {
            return legacy;
        }
        CachedOverride cached = cache.get(key);
        long now = System.nanoTime();
        if (cached != null && (now - cached.stamp()) < CACHE_TTL_NANOS) {
            return cached.value();
        }
        StringRedisTemplate redis = redisProvider == null ? null : redisProvider.getIfAvailable();
        if (redis == null) {
            return null;
        }
        try {
            Object raw = redis.opsForHash().get(OVERRIDE_HASH_KEY, key);
            if (raw == null) {
                cache.remove(key);
                return null;
            }
            Boolean value = Boolean.parseBoolean(String.valueOf(raw));
            cache.put(key, new CachedOverride(value, now));
            return value;
        } catch (Exception ex) {
            warnThrottled(key, ex);
            return null;
        }
    }

    /**
     * 灰度开关只读判断（键未定义/非 true 一律 false——与 @ConditionalOnProperty
     * havingValue="true" 缺省语义一致）。
     *
     * <p>MM-8：覆盖层非空时先查覆盖层（覆盖优先），为空时与原实现逐字节一致。</p>
     */
    public boolean enabled(String key) {
        Boolean override = readOverride(key);
        if (override != null) {
            return override;
        }
        return Boolean.TRUE.equals(environment.getProperty(key, Boolean.class));
    }

    /**
     * 全部已知灰度键的只读快照（键 → 当前布尔值，保持登记顺序）。
     *
     * <p>MM-8：覆盖层非空时附带 {@code overrides} 视图（键 → 覆盖值）；
     * 覆盖层为空（含开关默认 false）时输出与原实现逐字节一致。
     * E-4a：覆盖视图升级为 Redis hash 源视图（无 Redis/异常时回落本地缓存视图）。</p>
     */
    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (String key : KNOWN_KEYS) {
            snapshot.put(key, enabled(key));
        }
        Map<String, Boolean> view = overridesView();
        if (!view.isEmpty()) {
            snapshot.put("overrides", view);
        }
        return snapshot;
    }

    /** MM-8：动态写能力开关视图（false=写接口 405 且 override no-op）。 */
    public boolean isDynamicWriteEnabled() {
        return dynamicWriteEnabled;
    }

    /**
     * MM-8：写覆盖层（开关关闭或未知键返回 false 不产生任何效果）。
     * E-4a：门控通过后 HSET hash（value="true"/"false"）+本地缓存失效；
     * Redis 异常 fail-open（WARN 节流，返回 false=未写入）。
     *
     * @return true=覆盖已写入；false=被开关门控拒绝、键不在 KNOWN_KEYS 或 Redis 写失败
     */
    public boolean override(String key, boolean on) {
        if (!dynamicWriteEnabled || !KNOWN_KEYS.contains(key)) {
            return false;
        }
        StringRedisTemplate redis = redisProvider == null ? null : redisProvider.getIfAvailable();
        if (redis == null) {
            // 无 Redis 环境：保持 MM-8 既有内存覆盖语义（既有单测/无 Redis 装配行为不变）
            overrides.put(key, on);
            log.info("[GrayOverride] key={} on={} by=admin", key, on);
            return true;
        }
        try {
            redis.opsForHash().put(OVERRIDE_HASH_KEY, key, Boolean.toString(on));
            cache.remove(key);
            log.info("[GrayOverride] key={} on={} by=admin", key, on);
            return true;
        } catch (Exception ex) {
            warnThrottled(key, ex);
            return false;
        }
    }

    /**
     * MM-8：清除单键覆盖（恢复 Environment 取值；开关关闭返回 false no-op）。
     * E-4a：HDEL hash 字段+本地缓存失效；Redis 异常 fail-open（WARN 节流）。
     *
     * @return true=覆盖存在且已清除
     */
    public boolean clearOverride(String key) {
        if (!dynamicWriteEnabled) {
            return false;
        }
        StringRedisTemplate redis = redisProvider == null ? null : redisProvider.getIfAvailable();
        if (redis == null) {
            boolean removed = overrides.remove(key) != null;
            if (removed) {
                log.info("[GrayOverride] key={} on=cleared by=admin", key);
            }
            return removed;
        }
        try {
            Long removed = redis.opsForHash().delete(OVERRIDE_HASH_KEY, key);
            cache.remove(key);
            if (removed != null && removed > 0) {
                log.info("[GrayOverride] key={} on=cleared by=admin", key);
                return true;
            }
            return false;
        } catch (Exception ex) {
            warnThrottled(key, ex);
            return false;
        }
    }

    /** MM-8：清除全部覆盖（恢复 Environment 取值；开关关闭 no-op）。E-4a：DEL 整个 hash+清本地缓存；无 Redis 回落内存层。 */
    public void clearAllOverrides() {
        if (!dynamicWriteEnabled) {
            return;
        }
        StringRedisTemplate redis = redisProvider == null ? null : redisProvider.getIfAvailable();
        if (redis == null) {
            overrides.clear();
            log.info("[GrayOverride] all overrides cleared by=admin");
            return;
        }
        try {
            redis.delete(OVERRIDE_HASH_KEY);
            cache.clear();
            log.info("[GrayOverride] all overrides cleared by=admin");
        } catch (Exception ex) {
            warnThrottled("*", ex);
        }
    }

    /**
     * MM-8：覆盖层只读视图（键 → 覆盖值；供端点/快照消费，调用方不可修改）。
     * E-4a：Redis HGETALL 源视图；无 Redis 或异常时回落本地缓存视图（fail-open）。
     */
    public Map<String, Boolean> overridesView() {
        StringRedisTemplate redis = redisProvider == null ? null : redisProvider.getIfAvailable();
        if (redis == null) {
            return Map.copyOf(localCacheView());
        }
        try {
            Map<Object, Object> entries = redis.opsForHash().entries(OVERRIDE_HASH_KEY);
            Map<String, Boolean> view = new LinkedHashMap<>();
            entries.forEach((field, value) -> view.put(String.valueOf(field), Boolean.parseBoolean(String.valueOf(value))));
            return Map.copyOf(view);
        } catch (Exception ex) {
            warnThrottled("*", ex);
            return Map.copyOf(localCacheView());
        }
    }

    private Map<String, Boolean> localCacheView() {
        Map<String, Boolean> view = new LinkedHashMap<>();
        overrides.forEach(view::put);
        cache.forEach((key, entry) -> view.putIfAbsent(key, entry.value()));
        return view;
    }

    /** E-4a：Redis 异常 WARN 节流（每键每 60s 至多一条）。 */
    private void warnThrottled(String key, Exception ex) {
        long now = System.nanoTime();
        Long last = lastWarnAt.get(key);
        if (last == null || (now - last) >= WARN_THROTTLE_NANOS) {
            lastWarnAt.put(key, now);
            log.warn("[GrayOverride] redis unavailable, fail-open to environment: key={} err={}", key, ex.getMessage());
        }
    }

    /** E-4a：本地缓存条目（值+装载 nanoTime；包私有供同包单测构造过期样本）。 */
    record CachedOverride(boolean value, long stamp) {
    }
}
