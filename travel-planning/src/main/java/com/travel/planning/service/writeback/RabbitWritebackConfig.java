package com.travel.planning.service.writeback;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.common.config.GrayReleaseManager;
import com.travel.common.event.EventEnvelope;
import com.travel.planning.service.ItineraryDetailCache;
import com.travel.planning.service.ItinerarySliceWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Y-3d：writeback 消费端 rabbit 通道（type=rabbit 时装配；默认 redis=零装配，E-33）。
 *
 * <p><b>复用既有 Consumer 处理逻辑</b>：注入与 {@link WritebackEventConsumer} 相同的
 * {@link ItinerarySliceWriter}/{@link ItineraryDetailCache}/{@link GrayReleaseManager}，
 * 业务调用逐字一致（切片写回+缓存失效，均可安全重复执行）；灰度门复用同一键
 * {@code gray.writeback-consumer.enabled}。redis Stream 监听（@Scheduled 轮询）零改动并存。</p>
 *
 * <p><b>DLQ 映射</b>（redis 语义→rabbit 语义）：redis=失败不 ACK 重投、达 3 次转死信日志并 ACK；
 * rabbit=失败抛 {@link AmqpRejectAndDontRequeueException}（reject 不重入队）→经
 * {@code travel-event.dlx} 死信交换机落入 {@code travel-writeback-rabbit-dlq}——
 * 消息零丢失、人工按 DLQ 处置（比 redis 3 次重投更早进入人工处置面，终态语义一致）。
 * 灰度关闭=暂停消费：消息同样进 DLQ 积压（人工应急门，等价 redis 轮询头空转的积压语义）。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "travel.eventbus.type", havingValue = "rabbit")
public class RabbitWritebackConfig {

    /** StreamBridge 动态 destination（Y-3b 发端同名）；Topic 交换机（AR-4 修型，绑定 '#' 通配） */
    public static final String EXCHANGE = "travel-event";
    /** writeback rabbit 消费队列（带 DLX 声明） */
    public static final String QUEUE = "travel-writeback-rabbit";
    /** 死信交换机/队列（消费失败 reject 后落此，人工处置） */
    public static final String DLX = "travel-event.dlx";
    public static final String DLQ = "travel-writeback-rabbit-dlq";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> PAYLOAD_TYPE = new TypeReference<>() {
    };

    /** 拓扑声明：exchange/queue(带 DLX)/dlq/binding（幂等声明，RabbitAdmin 执行）。 */
    @Bean
    public Declarables rabbitWritebackTopology() {
        // AR-4（Y 审计修复）：主交换机=Topic——StreamBridge 动态目的地默认按 topic 声明，
        // 原 direct 声明与其撞型（PRECONDITION_FAILED 406 received 'topic' current 'direct'）
        // → 发送通道关闭→publish 异常→回退同步（AMQP 不通但功能不坏的原因）。
        // 绑定 '#' 通配=不依赖 StreamBridge 的具体 routing key。
        TopicExchange exchange = new TopicExchange(EXCHANGE, true, false);
        DirectExchange deadLetterExchange = new DirectExchange(DLX, true, false);
        Queue queue = QueueBuilder.durable(QUEUE)
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(DLQ)
                .build();
        Queue deadLetterQueue = QueueBuilder.durable(DLQ).build();
        return new Declarables(
                exchange, deadLetterExchange, queue, deadLetterQueue,
                BindingBuilder.bind(queue).to(exchange).with("#"),
                BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(DLQ));
    }

    @Bean
    public RabbitWritebackListener rabbitWritebackListener(ItinerarySliceWriter sliceWriter,
            ItineraryDetailCache itineraryDetailCache, GrayReleaseManager gray) {
        return new RabbitWritebackListener(sliceWriter, itineraryDetailCache, gray);
    }

    /**
     * rabbit 消费监听器：信封 JSON → 复用既有业务（gray 门+切片写回+缓存失效）。
     */
    @Slf4j
    static class RabbitWritebackListener {

        private final ItinerarySliceWriter sliceWriter;
        private final ItineraryDetailCache itineraryDetailCache;
        private final GrayReleaseManager gray;

        RabbitWritebackListener(ItinerarySliceWriter sliceWriter,
                ItineraryDetailCache itineraryDetailCache, GrayReleaseManager gray) {
            this.sliceWriter = sliceWriter;
            this.itineraryDetailCache = itineraryDetailCache;
            this.gray = gray;
        }

        @RabbitListener(queues = QUEUE)
        public void onMessage(String message) {
            EventEnvelope envelope;
            Map<String, String> payload;
            try {
                envelope = MAPPER.readValue(message, EventEnvelope.class);
                payload = MAPPER.readValue(envelope.payloadJson(), PAYLOAD_TYPE);
            } catch (Exception parseError) {
                // 载荷不可解析属不可恢复错误：reject→DLQ（redis 语义=ACK 丢弃；rabbit 留 DLQ 更安全）
                log.error("[WritebackDLQ] 载荷不可解析，reject 入死信: message={}", message);
                throw new AmqpRejectAndDontRequeueException("unparseable writeback payload", parseError);
            }
            if (!"REFINE".equals(envelope.type())) {
                log.warn("[WritebackConsumer] 非本消费域事件类型，忽略: type={}", envelope.type());
                return;
            }
            // 灰度门（与 WritebackEventConsumer.GRAY_KEY 同键）：关闭=暂停消费→DLQ 积压（人工应急）
            if (!gray.enabled(WritebackEventConsumer.GRAY_KEY)) {
                log.warn("[WritebackConsumer] 灰度暂停（{}=false），消息 reject 入死信积压", WritebackEventConsumer.GRAY_KEY);
                throw new AmqpRejectAndDontRequeueException("gray switch off: writeback consumer paused");
            }
            String sessionId = payload.getOrDefault("sessionId", "");
            String content = payload.getOrDefault("content", "");
            long itineraryId;
            try {
                itineraryId = Long.parseLong(payload.get("itineraryId"));
            } catch (Exception parseError) {
                log.error("[WritebackDLQ] itineraryId 不可解析，reject 入死信: payload={}", payload);
                throw new AmqpRejectAndDontRequeueException("unparseable itineraryId", parseError);
            }
            try {
                // 业务处理逐字复用 Consumer.process 成功路径（幂等：切片写回+缓存 evict）
                sliceWriter.writeAfterGenerated(sessionId, itineraryId, content);
                if (itineraryDetailCache != null) {
                    itineraryDetailCache.evict(itineraryId);
                }
                log.info("[WritebackConsumer] rabbit 通道事件已消费: itineraryId={}", itineraryId);
            } catch (Exception e) {
                log.error("[WritebackDLQ] rabbit 通道消费失败，reject 入死信（人工处置）: itineraryId={}, error={}",
                        itineraryId, e.getMessage());
                throw new AmqpRejectAndDontRequeueException("writeback processing failed", e);
            }
        }
    }
}
