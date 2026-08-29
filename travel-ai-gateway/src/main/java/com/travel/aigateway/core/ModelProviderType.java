package com.travel.aigateway.core;

/**
 * M7：模型 Provider 类型。
 *
 * <p>按「模型描述符」而非「厂商」建模（评审 D1）：DASHSCOPE_NATIVE 走 DashScope SDK
 * 原生端点；OPENAI_COMPATIBLE 走 OpenAI 兼容协议（DeepSeek/GLM/DashScope 兼容模式）。</p>
 */
public enum ModelProviderType {
    DASHSCOPE_NATIVE,
    OPENAI_COMPATIBLE;

    /** 宽松解析 yml 值：dashscope / dashscope-native / openai / openai-compatible。 */
    public static ModelProviderType parse(String value) {
        if (value == null || value.isBlank()) {
            throw new GatewayException("模型 provider 不能为空");
        }
        return switch (value.trim().toLowerCase()) {
            case "dashscope", "dashscope-native" -> DASHSCOPE_NATIVE;
            case "openai", "openai-compatible" -> OPENAI_COMPATIBLE;
            default -> throw new GatewayException("未知模型 provider: " + value);
        };
    }
}
