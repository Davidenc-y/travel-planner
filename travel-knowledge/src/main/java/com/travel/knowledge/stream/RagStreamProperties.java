package com.travel.knowledge.stream;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * M6-19：RAG 流式端点配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.knowledge.stream")
public class RagStreamProperties {

    private boolean enabled = true;

    private long timeoutMs = 120_000;

    private long keepaliveMs = 15_000;

    private int chunkMaxChars = 16;
}
