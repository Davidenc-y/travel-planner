package com.travel.aigateway.core;

/**
 * M10-2c：模型维度熔断 OPEN 异常（网关内自定义，服务层映射 40304）。
 */
public class ModelCircuitOpenException extends GatewayException {

    /** 业务码（与 travel-common ErrorCode.MODEL_CIRCUIT_OPEN 对齐，避免网关依赖 common） */
    public static final int CODE = 40304;

    public ModelCircuitOpenException(String message) {
        super(message);
    }
}
