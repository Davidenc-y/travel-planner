package com.travel.planning.cancellation;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * M6-44：Redis Pub/Sub 取消广播实现。
 *
 * <p>payload 为 {@code {"sessionId": ..., "clientMessageId": ...}} JSON；
 * 开关关闭或广播失败均不抛异常（权威路径为 DB 状态 + Redis 标记，
 * 二者已由调用方同步完成）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisTurnCancellationBroadcaster implements TurnCancellationBroadcaster {

    private final StringRedisTemplate redisTemplate;
    private final ChatCancellationPubSubProperties props;
    private final ObjectMapper objectMapper;

    @Override
    public void publishCancel(String sessionId, String clientMessageId) {
        if (!props.isEnabled() || sessionId == null || sessionId.isBlank()
                || clientMessageId == null || clientMessageId.isBlank()) {
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "sessionId", sessionId,
                    "clientMessageId", clientMessageId));
            redisTemplate.convertAndSend(props.getChannel(), payload);
        } catch (Exception e) {
            // 广播失败不影响本地取消与 Redis 标记（权威路径已同步完成）
            log.warn("[CancellationPub] 广播失败: key={}, err={}",
                    clientMessageId, e.getMessage());
        }
    }
}
