package com.travel.planning.cancellation;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * M6-44：轮次取消广播配置。
 *
 * <p>对应 yml：{@code travel.chat.cancellation.*}。默认开启；单实例部署或
 * Redis 不可用时置 {@code enabled=false} 走纯本地取消（正确性不受影响，
 * 仅跨实例推送加速失效）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.chat.cancellation")
public class ChatCancellationPubSubProperties {

    /** Pub/Sub 广播总开关 */
    private boolean enabled = true;

    /** 取消广播频道 */
    private String channel = "travel:chat:turn:cancel";
}
