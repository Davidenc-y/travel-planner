package com.travel.common.exception;

import lombok.Getter;

/**
 * 业务异常基类
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Getter
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
