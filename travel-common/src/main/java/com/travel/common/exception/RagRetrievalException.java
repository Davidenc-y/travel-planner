package com.travel.common.exception;

/**
 * RAG 检索异常
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
public class RagRetrievalException extends TravelBusinessException {

    public RagRetrievalException(String reason) {
        super(50002, "RAG 检索失败: " + reason);
    }

    public RagRetrievalException(String reason, Throwable cause) {
        super(50002, "RAG 检索失败: " + reason, cause);
    }
}
