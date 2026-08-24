package com.travel.core.stream;

/**
 * M6：流式事件元数据（统一协议头）。
 */
public record StreamMeta(
        String requestId,
        String sessionId,
        String clientMessageId,
        String domain,
        Integer code,
        boolean replayed) {
}
