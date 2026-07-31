package com.travel.common.exception;

/**
 * 旅游业务异常
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
public class TravelBusinessException extends BusinessException {

    public TravelBusinessException(int code, String message) {
        super(code, message);
    }

    public TravelBusinessException(int code, String message, Throwable cause) {
        super(code, message, cause);
    }
}
