package com.travel.planning.stream;

import com.travel.common.exception.ErrorCode;

/**
 * M6：业务错误码 → HTTP 状态映射（复用 travel-common ErrorCode 的 HTTP 对齐契约）。
 */
public final class StreamErrorMapper {

    private StreamErrorMapper() {
    }

    public static int httpStatus(Integer code) {
        if (code == null) {
            return 400;
        }
        return ErrorCode.of(code).httpStatus();
    }
}
