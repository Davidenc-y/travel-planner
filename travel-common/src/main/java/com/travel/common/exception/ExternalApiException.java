package com.travel.common.exception;

import lombok.Getter;

/**
 * 外部 API 调用异常
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Getter
public class ExternalApiException extends TravelBusinessException {

    private final String apiName;

    public ExternalApiException(String apiName, String reason) {
        super(50301, "[" + apiName + "] 调用失败: " + reason);
        this.apiName = apiName;
    }

    public ExternalApiException(String apiName, String reason, Throwable cause) {
        super(50301, "[" + apiName + "] 调用失败: " + reason, cause);
        this.apiName = apiName;
    }
}
