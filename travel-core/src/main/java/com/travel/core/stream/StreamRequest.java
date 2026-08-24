package com.travel.core.stream;

import java.util.Map;

/**
 * M6：领域流式请求（传输层无关）。
 */
public record StreamRequest(
        String domain,
        Long userId,
        String sessionId,
        String input,
        String clientMessageId,
        Map<String, Object> attributes,
        /** A-P2：客户端断线重连携带的 Last-Event-ID（1-based 事件序号） */
        String lastEventId) {

    public StreamRequest {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
