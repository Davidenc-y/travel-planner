package com.travel.planning.cancellation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.planning.service.TurnCancellationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * M6-44：轮次取消广播订阅者。
 *
 * <p>收到 {@code {"sessionId": ..., "clientMessageId": ...}} 后取消本地登记表
 * （幂等：重复/多实例广播无害）；非法 payload 仅 WARN 不抛。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TurnCancellationSubscriber implements MessageListener {

    private final TurnCancellationRegistry cancellationRegistry;
    private final ChatCancellationPubSubProperties props;
    private final ObjectMapper objectMapper;

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
            }
        } catch (Exception e) {
            log.warn("[CancellationSub] 忽略非法广播消息: {}", e.getMessage());
        }
    }
}
