package com.travel.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MI-6：灰度开关单点（只读视图，读 Environment；不含动态写——动态切换留人工批次）。
 *
 * <p>聚合现存灰度键全集（前置核验 2026-09-13：{@code gray.stream.webflux-enabled}、
 * {@code gray.brief.redis.enabled}；新增灰度键时在此登记 KNOWN_KEYS 即可进快照）。
 * {@code enabled(key)} 供消费点做字面判断（当前代码内零字面判断消费点——两键均为
 * 注解/条件装配驱动，消费点替换记零处）；{@code snapshot()} 输出全部已知键当前值，
 * 供 {@code gray.release.snapshot} 端点（AdminReliabilityController）只读输出。</p>
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
            "gray.brief.redis.enabled");

    private final Environment environment;

    public GrayReleaseManager(Environment environment) {
        this.environment = environment;
    }

    /**
     * 灰度开关只读判断（键未定义/非 true 一律 false——与 @ConditionalOnProperty
     * havingValue="true" 缺省语义一致）。
     */
    public boolean enabled(String key) {
        return Boolean.TRUE.equals(environment.getProperty(key, Boolean.class));
    }

    /**
     * 全部已知灰度键的只读快照（键 → 当前布尔值，保持登记顺序）。
     */
    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (String key : KNOWN_KEYS) {
            snapshot.put(key, enabled(key));
        }
        return snapshot;
    }
}
