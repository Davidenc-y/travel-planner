package com.travel.aigateway.core;

import java.util.List;

/**
 * M7：模型描述符（注册表单条目的运行时形态，也是 yml 绑定目标）。
 *
 * <p>字段对齐评审 03 §6.1：key（透传 SDK 的模型标识）、provider、displayName、
 * endpointMode（DashScope native/compatible）、baseUrl（OPENAI_COMPATIBLE 必填）、
 * apiKeyEnv（只存环境变量名，D7）、temperature/maxTokens/topP（null=不传）、
 * timeoutSeconds（per-model 超时，null=全局默认）、roles（main/light/embedding/rerank）、
 * selectable（是否对前端可选）、enabled（配额/下线开关）。</p>
 */
public record ModelDescriptor(
        String key,
        ModelProviderType provider,
        String displayName,
        String endpointMode,
        String baseUrl,
        String apiKeyEnv,
        Double temperature,
        Integer maxTokens,
        Double topP,
        Integer timeoutSeconds,
        List<String> roles,
        boolean selectable,
        boolean enabled) {

    public ModelDescriptor {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    public ModelDescriptor withEnabled(boolean enabled) {
        return new ModelDescriptor(key, provider, displayName, endpointMode, baseUrl,
                apiKeyEnv, temperature, maxTokens, topP, timeoutSeconds, roles, selectable, enabled);
    }
}
