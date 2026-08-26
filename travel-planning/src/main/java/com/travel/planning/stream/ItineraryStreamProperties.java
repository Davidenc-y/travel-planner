package com.travel.planning.stream;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * M6-15 Item 4：行程流式端点配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.itinerary.stream")
public class ItineraryStreamProperties {

    private boolean enabled = true;

    /** SseEmitter 超时（毫秒），覆盖行程图 300s 硬超时 */
    private long timeoutMs = 330_000;

    private long keepaliveMs = 15_000;

    private int chunkMaxChars = 16;
}
