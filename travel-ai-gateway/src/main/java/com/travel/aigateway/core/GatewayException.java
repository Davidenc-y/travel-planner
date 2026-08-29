package com.travel.aigateway.core;

/**
 * M7：模型网关异常。
 *
 * <p>用于注册表校验失败、模型 key 缺失/未启用、工厂创建失败等路由层错误；
 * 调用方（Controller/领域层）按需转换为业务错误码（Batch 2 接入 MODEL_NOT_FOUND）。</p>
 */
public class GatewayException extends RuntimeException {

    public GatewayException(String message) {
        super(message);
    }

    public GatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
