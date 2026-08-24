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
        Map<String, Object> attributes) {

    public StreamRequest {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
