package com.travel.common.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4 联调轮：HTTP 状态对齐契约守护——40102/40103/40003/40404 必须映射为
 * 401/401/409/404（此前未登记 ErrorCode 被 GlobalExceptionHandler 兜底为 500）。
 */
class ErrorCodeMappingTest {

    @Test
    void authAndSessionCodesMapToProperHttpStatus() {
        assertThat(ErrorCode.AUTH_TOKEN_INVALID.httpStatus()).isEqualTo(401);
        assertThat(ErrorCode.AUTH_TOKEN_EXPIRED.httpStatus()).isEqualTo(401);
        assertThat(ErrorCode.USERNAME_EXISTS.httpStatus()).isEqualTo(409);
        assertThat(ErrorCode.EMAIL_EXISTS.httpStatus()).isEqualTo(409);
        assertThat(ErrorCode.SESSION_NOT_FOUND.httpStatus()).isEqualTo(404);
    }

    @Test
    void ofRoundTripsRegisteredCodes() {
        assertThat(ErrorCode.of(40102)).isEqualTo(ErrorCode.AUTH_TOKEN_INVALID);
        assertThat(ErrorCode.of(40103)).isEqualTo(ErrorCode.AUTH_TOKEN_EXPIRED);
        assertThat(ErrorCode.of(40003)).isEqualTo(ErrorCode.USERNAME_EXISTS);
        assertThat(ErrorCode.of(40004)).isEqualTo(ErrorCode.EMAIL_EXISTS);
        assertThat(ErrorCode.of(40404)).isEqualTo(ErrorCode.SESSION_NOT_FOUND);
    }
}
