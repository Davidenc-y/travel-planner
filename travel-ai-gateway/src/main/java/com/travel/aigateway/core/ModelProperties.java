package com.travel.aigateway.core;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * M7：模型注册表配置绑定（{@code travel.ai.model-registry.*}）。
 *
 * <p>enabled=false（或缺失）时 GatewayAutoConfig 不装配，退回旧 AiModelConfig 行为
 * （回滚开关，Batch 2 接入后生效）；models 为注册表唯一事实源。</p>
 */
@ConfigurationProperties(prefix = "travel.ai.model-registry")
public record ModelProperties(
        boolean enabled,
        int defaultTimeoutSeconds,
        List<ModelDescriptor> models) {

    public ModelProperties {
        models = models == null ? List.of() : List.copyOf(models);
        if (defaultTimeoutSeconds <= 0) {
            defaultTimeoutSeconds = 120;
        }
    }
}
