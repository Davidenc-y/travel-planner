package com.travel.planning.service.writeback;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.common.event.EventBusPort;
import com.travel.common.event.EventEnvelope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * HC-5：writeback 异步削峰事件发布器（Y-3a 起经 {@link EventBusPort} 发布）。
 *
 * <p>选型辩证（方案 §六 HC-5）：<b>RabbitMQ 弃</b>——新增中间件运维面；
 * <b>canal 弃</b>——binlog 权限+部署面，且本场景是应用级事件而非数据复制；
 * <b>Redis Stream 取</b>——复用现有 Redis 与爬虫 XACK 消费组先例。</p>
 *
 * <p>语义边界（方案 §六 HC-5）：版本落库与约束列更新仍在聊天轮内完成（用户可见一致性）；
 * 仅 ETL 类副作用（会话知识切片写回、行程详情缓存失效广播）经本发布器进入异步消费组。
 * 发布失败返回 false，由调用方回退同步执行（开关 perf.writeback-async.enabled 双路径观察期）。</p>
 *
 * <p>Y-3a：XADD 语义下沉 {@link com.travel.common.event.RedisStreamEventBus}（字节等价：
 * 字段面 type/sessionId/itineraryId/content、stream key 不变）。Y-3c：type 路由由容器完成——
 * 默认 type=redis 唯一 Bean=RedisStreamEventBus；type=rabbit 时 RabbitEventBusConfig 的
 * &#64;Primary rabbit Bean 接管注入（单点 {@link EventBusPort} 注入零改动）。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WritebackEventPublisher {

    /** writeback 事件 Stream key（消费组见 WritebackEventConsumer） */
    public static final String STREAM_KEY = "travel:chat:writeback";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EventBusPort eventBus;

    /**
     * 发布 REFINE 写库后的 ETL 类副作用事件。
     *
     * @return true=发布成功（副作用交异步消费组）；false=发布失败（调用方回退同步执行）
     */
    public boolean publishRefineWritten(String sessionId, Long itineraryId, String content) {
        Map<String, String> payload = Map.of(
                "sessionId", sessionId == null ? "" : sessionId,
                "itineraryId", String.valueOf(itineraryId),
                "content", content == null ? "" : content);
        try {
            EventEnvelope envelope = new EventEnvelope(
                    "REFINE", sessionId == null ? "" : sessionId,
                    MAPPER.writeValueAsString(payload), System.currentTimeMillis());
            boolean published = eventBus.publish(envelope);
            if (published) {
                log.info("[WritebackPublisher] 事件已发布: itineraryId={}, sessionId={}", itineraryId, sessionId);
            } else {
                log.warn("[WritebackPublisher] XADD 失败（调用方回退同步执行）: itineraryId={}, error=bus returned false",
                        itineraryId);
            }
            return published;
        } catch (JsonProcessingException e) {
            log.warn("[WritebackPublisher] XADD 失败（调用方回退同步执行）: itineraryId={}, error={}",
                    itineraryId, e.getMessage());
            return false;
        }
    }
}
