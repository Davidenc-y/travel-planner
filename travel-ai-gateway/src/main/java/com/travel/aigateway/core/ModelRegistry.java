package com.travel.aigateway.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * M7：模型注册表——启动加载、校验与查询的单一事实源。
 *
 * <p>校验：key 唯一；apiKeyEnv 缺失（环境/System.getenv 均无）→ 该模型标记 disabled + WARN
 * （不阻断启动，D7）。查询：get / requireSelectable（未注册/未启用/不可选抛 GatewayException，
 * D6 入口快速失败语义的注册表侧实现）/ defaultOf(role)（角色默认，跳过 disabled）。</p>
 */
@Slf4j
public class ModelRegistry {

    private final Map<String, ModelDescriptor> byKey;
    private final Map<String, List<ModelDescriptor>> byRole;

    public ModelRegistry(ModelProperties properties, Environment environment) {
        Map<String, ModelDescriptor> raw = new LinkedHashMap<>();
        for (ModelDescriptor d : properties.models()) {
            ModelDescriptor prev = raw.putIfAbsent(d.key(), d);
            if (prev != null) {
                throw new IllegalArgumentException("模型注册表 key 重复: " + d.key());
            }
        }

        Map<String, ModelDescriptor> resolved = new LinkedHashMap<>();
        for (ModelDescriptor d : raw.values()) {
            ModelDescriptor current = d;
            if (current.enabled() && missingApiKeyEnv(environment, current)) {
                log.warn("[ModelRegistry] 模型 {} 的 apiKeyEnv={} 未配置，标记 disabled",
                        current.key(), current.apiKeyEnv());
                current = current.withEnabled(false);
            }
            resolved.put(current.key(), current);
        }
        // 保持 yml 声明顺序（selectable 列表与角色默认的确定性来源）
        this.byKey = Collections.unmodifiableMap(new LinkedHashMap<>(resolved));

        Map<String, List<ModelDescriptor>> roles = new LinkedHashMap<>();
        for (ModelDescriptor d : resolved.values()) {
            for (String role : d.roles()) {
                roles.computeIfAbsent(role, k -> new ArrayList<>()).add(d);
            }
        }
        Map<String, List<ModelDescriptor>> frozen = new LinkedHashMap<>();
        roles.forEach((role, list) -> frozen.put(role, List.copyOf(list)));
        this.byRole = Collections.unmodifiableMap(frozen);

        log.info("[ModelRegistry] 模型注册表加载: enabled={}, models={}, selectable={}, "
                        + "main默认={}, light默认={}",
                properties.enabled(), byKey.size(),
                listEnabledSelectable().stream().map(ModelDescriptor::key).toList(),
                safeDefault("main"), safeDefault("light"));
    }

    private static boolean missingApiKeyEnv(Environment environment, ModelDescriptor d) {
        if (d.apiKeyEnv() == null || d.apiKeyEnv().isBlank()) {
            return false;
        }
        String property = environment != null ? environment.getProperty(d.apiKeyEnv()) : null;
        String systemEnv = System.getenv(d.apiKeyEnv());
        return (property == null || property.isBlank()) && (systemEnv == null || systemEnv.isBlank());
    }

    private String safeDefault(String role) {
        try {
            return defaultOf(role).key();
        } catch (GatewayException e) {
            return null;
        }
    }

    public Optional<ModelDescriptor> get(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    /** D6：请求级 model 必须已注册、enabled 且 selectable，否则抛 GatewayException。 */
    public ModelDescriptor requireSelectable(String key) {
        ModelDescriptor d = byKey.get(key);
        if (d == null) {
            throw new GatewayException("模型未注册: " + key);
        }
        if (!d.enabled()) {
            throw new GatewayException("模型未启用: " + key);
        }
        if (!d.selectable()) {
            throw new GatewayException("模型不可选择: " + key);
        }
        return d;
    }

    /** 角色默认模型（跳过 disabled；无可用默认抛 GatewayException）。 */
    public ModelDescriptor defaultOf(String role) {
        List<ModelDescriptor> candidates = byRole.getOrDefault(role, List.of());
        return candidates.stream()
                .filter(ModelDescriptor::enabled)
                .findFirst()
                .orElseThrow(() -> new GatewayException("角色缺少可用默认模型: " + role));
    }

    /** 前端模型清单（enabled 且 selectable；Batch 2 /api/v1/models 使用）。 */
    public List<ModelDescriptor> listEnabledSelectable() {
        Set<String> keys = new LinkedHashSet<>();
        List<ModelDescriptor> out = new ArrayList<>();
        for (ModelDescriptor d : byKey.values()) {
            if (d.enabled() && d.selectable() && keys.add(d.key())) {
                out.add(d);
            }
        }
        return List.copyOf(out);
    }
}
