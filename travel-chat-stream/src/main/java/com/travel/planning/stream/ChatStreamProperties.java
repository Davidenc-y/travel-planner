package com.travel.planning.stream;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * M6：聊天流式端点配置。
 *
 * <p>对应 yml：{@code travel.chat.stream.*}；timeout 默认 330s，
 * 覆盖 Supervisor 300s 硬超时并留余量。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.chat.stream")
public class ChatStreamProperties {

    /** 流式端点总开关（false → 404，前端自动回退 JSON） */
    private boolean enabled = true;

    /** SseEmitter 超时（毫秒） */
    private long timeoutMs = 330_000;

    /** ping 保活间隔（毫秒） */
    private long keepaliveMs = 15_000;

    /** 最终回答分块大小（字符） */
    private int chunkMaxChars = 16;
}
