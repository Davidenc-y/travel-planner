package com.travel.planning.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.common.event.EventBusPort;
import com.travel.common.event.EventEnvelope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Y-3b：事件总线 rabbit 通道装配（writeback 试点）。
 *
 * <p><b>E-33 关闭态等价</b>：{@code travel.eventbus.type=redis}（默认，键缺省同值）时
 * 本配置零装配——RabbitEventBus/StreamBridge 桥接 Bean 均不存在；spring-cloud-stream
 * binder 无 binding 配置=惰性零连接；{@code management.health.rabbit.enabled=false}
 * 显式默认关（r1：防 actuator 聚合 rabbit 健康误报 DOWN）。</p>
 *
 * <p>type=rabbit 时装配 {@link RabbitEventBus}：spring-cloud-stream {@link StreamBridge}
 * 发 {@link #TRAVEL_EVENT_CHANNEL} channel，信封整体 JSON 化为消息载荷（type/key/payloadJson/ts
 * 全量传输——redis 通道仅扁平 type+载荷字段，两通道消息体不同属预期，对照实弹见 Y-3d）。
 * 发布失败返回 false 不抛出（EventBusPort 契约，调用方回退同步执行）。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "travel.eventbus.type", havingValue = "rabbit")
public class RabbitEventBusConfig {

    /** rabbit 通道名（StreamBridge 目标 binding；队列拓扑与实弹归审计，见 RabbitMQ实弹手册） */
    public static final String TRAVEL_EVENT_CHANNEL = "travel-event";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Y-3c：type 路由消解——type=rabbit 时 RedisStreamEventBus（@Component 无条件）与本 Bean
     * 并存为两个 EventBusPort，@Primary 使 Publisher 的单点注入路由到 rabbit 通道；
     * 默认 type=redis 时本配置零装配、唯一 Bean=RedisStreamEventBus（注入无歧义）。
     */
    @Bean
    @Primary
    public EventBusPort rabbitEventBus(StreamBridge streamBridge) {
        return new RabbitEventBus(streamBridge);
    }

    /**
     * rabbit 通道实现：信封 JSON → StreamBridge 发 travel-event。
     */
    static class RabbitEventBus implements EventBusPort {

        private final StreamBridge streamBridge;

        RabbitEventBus(StreamBridge streamBridge) {
            this.streamBridge = streamBridge;
        }

        @Override
        public boolean publish(EventEnvelope envelope) {
            try {
                boolean sent = streamBridge.send(TRAVEL_EVENT_CHANNEL, MAPPER.writeValueAsString(envelope));
                if (!sent) {
                    log.warn("[RabbitEventBus] StreamBridge 发送失败（未路由到 binding）: type={}, key={}",
                            envelope.type(), envelope.key());
                }
                return sent;
            } catch (Exception e) {
                log.warn("[RabbitEventBus] 发送异常（调用方回退同步执行）: type={}, key={}, error={}",
                        envelope.type(), envelope.key(), e.getMessage());
                return false;
            }
        }
    }
}
