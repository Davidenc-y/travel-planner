package com.travel.common.event;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Y-3a：EventBusPort 的 Redis Stream 等价实现（writeback 试点）。
 *
 * <p>现有 writeback Stream 语义<b>逐字包裹</b>：XADD 到 writeback Stream，字段面=
 * {@code type} + 信封载荷扁平字段（sessionId/itineraryId/content），与改造前
 * WritebackEventPublisher 的 XADD 逐字段等价（消费端 WritebackEventConsumer 按字段名
 * 读取，零改动兼容）；stream key/group/gray 键不变。</p>
 *
 * <p>发布失败（Redis 异常/载荷解析）返回 false 并 warn，不抛出——调用方回退同步执行。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Component
public class RedisStreamEventBus implements EventBusPort {

    /** writeback Stream key（与 WritebackEventPublisher.STREAM_KEY 同源，Y-3a 逐字包裹） */
    public static final String DEFAULT_STREAM_KEY = "travel:chat:writeback";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> PAYLOAD_TYPE = new TypeReference<>() {
    };

    private final StringRedisTemplate redisTemplate;
    private final String streamKey;

    public RedisStreamEventBus(StringRedisTemplate redisTemplate,
            @Value("${travel.eventbus.redis.stream-key:" + DEFAULT_STREAM_KEY + "}") String streamKey) {
        this.redisTemplate = redisTemplate;
        this.streamKey = streamKey;
    }

    @Override
    public boolean publish(EventEnvelope envelope) {
        try {
            Map<String, String> payload = MAPPER.readValue(envelope.payloadJson(), PAYLOAD_TYPE);
            Map<String, String> fields = new HashMap<>(payload.size() + 1);
            fields.put("type", envelope.type());
            fields.putAll(payload);
            redisTemplate.opsForStream().add(streamKey, fields);
            return true;
        } catch (Exception e) {
            log.warn("[RedisStreamEventBus] XADD 失败: type={}, key={}, error={}",
                    envelope.type(), envelope.key(), e.getMessage());
            return false;
        }
    }
}
