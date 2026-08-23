package com.travel.planning.memory.pipeline;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * M4-3/P0-3：聊天消息幂等配置。
 *
 * <p>对应 yml：{@code travel.chat.idempotency.enabled}。不带幂等键的请求始终走
 * 原路径（灰度双轨），开关仅控制带键请求是否启用幂等门禁。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.chat.idempotency")
public class ChatIdempotencyProperties {

    /** 是否启用消息级幂等（带 clientMessageId 的请求走幂等门禁） */
    private boolean enabled = true;
}
