package com.travel.planning.cancellation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.common.event.EventConsumerRegistry;
import com.travel.stream.service.TurnCancellationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * M6-44：轮次取消广播订阅者。
 *
 * <p>收到 {@code {"sessionId": ..., "clientMessageId": ...}} 后取消本地登记表
 * （幂等：重复/多实例广播无害）；非法 payload 仅 WARN 不抛。</p>
 *
 * <p>AR-5（2026-09-30 审计）：onMessage 挂接 AK-3d 通道计数打点
 * （成功取消=recordConsumed，非法 payload=recordRejected；ObjectProvider 软化，
 * 窄测试上下文缺 registry bean=跳过打点，取消语义零变化）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TurnCancellationSubscriber implements MessageListener {

    /** AR-5：注册中心 consumerKey（与 ChatCancellationPubSubConfig 注册行同源）。 */
    static final String CONSUMER_KEY = "chat-cancellation";

    private final TurnCancellationRegistry cancellationRegistry;
    private final ChatCancellationPubSubProperties props;
    private final ObjectMapper objectMapper;
    /** AR-5：观测面打点（getIfAvailable()=null 时零行为）。 */
    private final ObjectProvider<EventConsumerRegistry> consumerRegistryProvider;

    @Override
    @SuppressWarnings("unchecked")
    public void onMessage(Message message, byte[] pattern) {
        if (!props.isEnabled() || message == null || message.getBody() == null) {
            return;
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(
                    message.getBody(), Map.class);
            Object key = payload.get("clientMessageId");
            if (key instanceof String s && !s.isBlank()) {
                cancellationRegistry.cancel(s);
                recordConsumed();
            }
        } catch (Exception e) {
            log.warn("[CancellationSub] 忽略非法广播消息: {}", e.getMessage());
            recordRejected();
        }
    }

    private void recordConsumed() {
        EventConsumerRegistry registry = consumerRegistryProvider.getIfAvailable();
        if (registry != null) {
            registry.recordConsumed(CONSUMER_KEY);
        }
    }

    private void recordRejected() {
        EventConsumerRegistry registry = consumerRegistryProvider.getIfAvailable();
        if (registry != null) {
            registry.recordRejected(CONSUMER_KEY);
        }
    }
}
