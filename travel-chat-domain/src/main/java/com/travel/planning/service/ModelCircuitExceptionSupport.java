package com.travel.planning.service;

import com.travel.aigateway.core.ModelCircuitOpenException;
import com.travel.common.exception.BusinessException;
import com.travel.common.exception.ErrorCode;

/**
 * M10-2c：模型熔断异常识别与 40304 透传（镜像 ModelQuotaExceptionSupport 语义）。
 */
public final class ModelCircuitExceptionSupport {

    private ModelCircuitExceptionSupport() {
    }

    public static boolean isCircuitOpen(Throwable e) {
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 16) {
            if (cur instanceof ModelCircuitOpenException) {
                return true;
            }
            if (cur instanceof BusinessException be
                    && be.getCode() == ErrorCode.MODEL_CIRCUIT_OPEN.code()) {
                return true;
            }
            cur = cur.getCause();
            depth++;
        }
        return false;
    }

    /** 命中则上抛 40304（避免被兜底文案吞掉）；未命中原样返回。 */
    public static void rethrowIfCircuitOpen(Throwable e) {
        if (isCircuitOpen(e)) {
            throw new BusinessException(ErrorCode.MODEL_CIRCUIT_OPEN.code(),
                    ErrorCode.MODEL_CIRCUIT_OPEN.message());
        }
    }
}
