package com.travel.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
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
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Component
public class GrayReleaseManager {

    /** 现存灰度键全集（新增灰度键在此登记即进快照）。 */
    public static final List<String> KNOWN_KEYS = List.of(
            "gray.stream.webflux-enabled",
            "gray.brief.redis.enabled",
            "gray.itinerary-detail-cache.enabled",
            "gray.writeback-consumer.enabled");

    private final Environment environment;

    /** MM-8：动态写内存覆盖层（key → 覆盖值；仅内存，重启即失效）。 */
    private final ConcurrentHashMap<String, Boolean> overrides = new ConcurrentHashMap<>();

    /** MM-8：动态写能力开关（默认 false；置 true 属 E-19④ 人工闸门决策，执行器不得代行）。 */
    @Value("${travel.gray.dynamic-write.enabled:false}")
    private boolean dynamicWriteEnabled;

    public GrayReleaseManager(Environment environment) {
        this.environment = environment;
    }

    /**
     * 灰度开关只读判断（键未定义/非 true 一律 false——与 @ConditionalOnProperty
     * havingValue="true" 缺省语义一致）。
     *
     * <p>MM-8：覆盖层非空时先查覆盖层（覆盖优先），为空时与原实现逐字节一致。</p>
     */
    public boolean enabled(String key) {
        Boolean override = overrides.get(key);
        if (override != null) {
            return override;
        }
        return Boolean.TRUE.equals(environment.getProperty(key, Boolean.class));
    }

    /**
     * 全部已知灰度键的只读快照（键 → 当前布尔值，保持登记顺序）。
     *
     * <p>MM-8：覆盖层非空时附带 {@code overrides} 视图（键 → 覆盖值）；
     * 覆盖层为空（含开关默认 false）时输出与原实现逐字节一致。</p>
     */
    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (String key : KNOWN_KEYS) {
            snapshot.put(key, enabled(key));
        }
        if (!overrides.isEmpty()) {
            snapshot.put("overrides", Map.copyOf(overrides));
        }
        return snapshot;
    }

    /** MM-8：动态写能力开关视图（false=写接口 405 且 override no-op）。 */
    public boolean isDynamicWriteEnabled() {
        return dynamicWriteEnabled;
    }

    /**
     * MM-8：写覆盖层（开关关闭或未知键返回 false 不产生任何效果）。
     *
     * @return true=覆盖已写入；false=被开关门控拒绝或键不在 KNOWN_KEYS
     */
    public boolean override(String key, boolean on) {
        if (!dynamicWriteEnabled || !KNOWN_KEYS.contains(key)) {
            return false;
        }
        overrides.put(key, on);
        log.info("[GrayOverride] key={} on={} by=admin", key, on);
        return true;
    }

    /**
     * MM-8：清除单键覆盖（恢复 Environment 取值；开关关闭返回 false no-op）。
     *
     * @return true=覆盖存在且已清除
     */
    public boolean clearOverride(String key) {
        if (!dynamicWriteEnabled) {
            return false;
        }
        boolean removed = overrides.remove(key) != null;
        if (removed) {
            log.info("[GrayOverride] key={} on=cleared by=admin", key);
        }
        return removed;
    }

    /** MM-8：清除全部覆盖（恢复 Environment 取值；开关关闭 no-op）。 */
    public void clearAllOverrides() {
        if (!dynamicWriteEnabled || overrides.isEmpty()) {
            return;
        }
        overrides.clear();
        log.info("[GrayOverride] all overrides cleared by=admin");
    }

    /** MM-8：覆盖层只读视图（键 → 覆盖值；供端点/快照消费，调用方不可修改）。 */
    public Map<String, Boolean> overridesView() {
        return Map.copyOf(overrides);
    }
}
