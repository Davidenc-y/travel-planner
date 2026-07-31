package com.travel.common.exception;

/**
 * 行程生成异常
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
public class ItineraryGenerationException extends TravelBusinessException {

    public ItineraryGenerationException(String reason) {
        super(50001, "行程生成失败: " + reason);
    }

    public ItineraryGenerationException(String reason, Throwable cause) {
        super(50001, "行程生成失败: " + reason, cause);
    }
}
