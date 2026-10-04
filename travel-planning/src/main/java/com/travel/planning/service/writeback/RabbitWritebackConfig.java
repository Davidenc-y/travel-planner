package com.travel.planning.service.writeback;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.common.config.GrayReleaseManager;
import com.travel.common.event.EventConsumerRegistry;
import com.travel.common.event.EventEnvelope;
import com.travel.planning.service.ItineraryDetailCache;
import com.travel.planning.service.itinerary.ItinerarySliceWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import java.util.Arrays;
import java.util.Map;

/**
 * Y-3d：writeback 消费端 rabbit 通道（type=rabbit 时装配；默认 redis=零装配，E-33）。
 *
 * <p><b>复用既有 Consumer 处理逻辑</b>：注入与 {@link WritebackEventConsumer} 相同的
 * {@link ItinerarySliceWriter}/{@link ItineraryDetailCache}/{@link GrayReleaseManager}，
 * 业务调用逐字一致（切片写回+缓存失效，均可安全重复执行）；灰度门复用同一键
 * {@code gray.writeback-consumer.enabled}。redis Stream 监听（@Scheduled 轮询）零改动并存。</p>
 *
 * <p><b>DLQ 映射</b>（redis 语义→rabbit 语义，AZ-1/AZ-2 增强）：redis=失败不 ACK 重投、达 3 次转死信日志并 ACK；
 * rabbit=失败先经 spring-retry 重投 3 次（1s×2 倍退避），耗尽后 WritebackMessageRecoverer 按终因分流——
 * 真失败/不可解析 republish 至 {@code travel-event.dlx}→{@code travel-writeback-rabbit-dlq}
 * （消息零丢失、人工按 DLQ 处置）；灰度关闭=暂停消费：{@link WritebackPausedException} 分类为不可重试
 * →requeue 回主队列（主语义仍=AK-1c 容器停；DLQ 只收处理失败，不收人为暂停）。</p>
 *
 * <p>AK-1c：挂接 {@link EventConsumerRegistry} 统一观测面（listener 装配时注册
 * channel/consumerKey/gray 键/启停动作，启停=经 {@link RabbitListenerEndpointRegistry}
 * 按队列名协调真实容器 lifecycle）；<b>触发逻辑逐字保留</b>（@RabbitListener 声明与
 * onMessage 业务路径零改动=P0⑲）——实际暂停门仍为既有 gray 键，registry 启停为
 * 观测面协调动作（默认不调用，行为等价）。</p>
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

    /** 拓扑声明：exchange/queue(带 DLX)/dlq/binding（幂等声明，RabbitAdmin 执行）。
     * BA-1：quorum 开关（缺省 false=classic 分支与基线逐字节相同，P0-BA⑴）——
     * quorum 分支为集群/主从消息高可用前提（classic 队列消息仅存单节点）；
     * deliveryLimit(1_000_000) 覆盖 3.13 默认 20（P0-BA⑵：灰度暂停 1s requeue 循环
     * 的存活前提——默认 20 次即 dead-letter 会复活"暂停丢事件"语义）。 */
    @Bean
    public Declarables rabbitWritebackTopology(
            @Value("${travel.rabbit.writeback.quorum.enabled:false}") boolean quorumEnabled) {
        // AR-4（Y 审计修复）：主交换机=Topic——StreamBridge 动态目的地默认按 topic 声明，
        // 原 direct 声明与其撞型（PRECONDITION_FAILED 406 received 'topic' current 'direct'）
        // → 发送通道关闭→publish 异常→回退同步（AMQP 不通但功能不坏的原因）。
        // 绑定 '#' 通配=不依赖 StreamBridge 的具体 routing key。
        TopicExchange exchange = new TopicExchange(EXCHANGE, true, false);
        DirectExchange deadLetterExchange = new DirectExchange(DLX, true, false);
        QueueBuilder mainBuilder = QueueBuilder.durable(QUEUE)
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(DLQ);
        QueueBuilder dlqBuilder = QueueBuilder.durable(DLQ);
        if (quorumEnabled) {
            mainBuilder.quorum().deliveryLimit(1_000_000);
            dlqBuilder.quorum().deliveryLimit(1_000_000);
        }
        Queue queue = mainBuilder.build();
        Queue deadLetterQueue = dlqBuilder.build();
        return new Declarables(
                exchange, deadLetterExchange, queue, deadLetterQueue,
                BindingBuilder.bind(queue).to(exchange).with("#"),
                BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(DLQ));
    }

    /** AZ-1：writeback 专属容器工厂——spring-retry 无状态重试（默认 3 次、1s 起倍退避、
     * 上限 10s），耗尽经 WritebackMessageRecoverer 分流（暂停→requeue；真失败→DLQ）。
     * prefetch 与 spring.rabbitmq.listener.simple.prefetch 同键（AZ-4 暴露面一致）。 */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitWritebackContainerFactory(
            ConnectionFactory connectionFactory,
            RabbitTemplate rabbitTemplate,
            @Value("${travel.rabbit.writeback.retry.max-attempts:3}") int maxAttempts,
            @Value("${travel.rabbit.writeback.retry.backoff-initial-ms:1000}") long backoffInitialMs,
            @Value("${travel.rabbit.writeback.retry.backoff-multiplier:2.0}") double backoffMultiplier,
            @Value("${spring.rabbitmq.listener.simple.prefetch:250}") int prefetch) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setConcurrentConsumers(1);
        factory.setPrefetchCount(prefetch);
        RetryTemplate retryTemplate = new RetryTemplate();
        // 4 参构造（正确用法）：全部异常可重试（defaultValue=true），仅暂停异常除外
        retryTemplate.setRetryPolicy(new SimpleRetryPolicy(
                maxAttempts, Map.of(WritebackPausedException.class, false), true, true));
        ExponentialBackOffPolicy backOff = new ExponentialBackOffPolicy();
        backOff.setInitialInterval(backoffInitialMs);
        backOff.setMultiplier(backoffMultiplier);
        backOff.setMaxInterval(10000L);
        retryTemplate.setBackOffPolicy(backOff);
        // AZ-1 核验修正（判例 R376 包径实锚）：方案骨架 StatelessRetryOperationsInterceptor 在
        // spring-rabbit 3.2.6 不存在（jar 常量池实证），以 RetryInterceptorBuilder.stateless()
        // 等价改写——Boot 3.5.5 AbstractRabbitListenerContainerFactoryConfigurer 官方同款路径
        // （retryOperations+recoverer+build），重试策略/退避/恢复器分流语义逐字不变。
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .retryOperations(retryTemplate)
                .recoverer(new WritebackMessageRecoverer(rabbitTemplate))
                .build());
        return factory;
    }

    /**
     * AK-1c：listener 装配时挂接 EventConsumerRegistry（观测面+真实容器启停协调）。
     * 两依赖均 ObjectProvider 软化：窄测试上下文（仅装配本类）缺 bean=降级跳过注册，
     * 生产面 EventConsumerRegistry 经 CommonMarker 组件扫描必在。
     */
    @Bean
    public RabbitWritebackListener rabbitWritebackListener(ItinerarySliceWriter sliceWriter,
            ItineraryDetailCache itineraryDetailCache, GrayReleaseManager gray,
            ObjectProvider<EventConsumerRegistry> consumerRegistryProvider,
            ObjectProvider<RabbitListenerEndpointRegistry> listenerRegistryProvider) {
        RabbitWritebackListener listener = new RabbitWritebackListener(sliceWriter, itineraryDetailCache, gray);
        EventConsumerRegistry consumerRegistry = consumerRegistryProvider.getIfAvailable();
        if (consumerRegistry != null) {
            consumerRegistry.register("rabbit", "writeback-rabbit", WritebackEventConsumer.GRAY_KEY,
                    () -> toggleListenerContainers(listenerRegistryProvider.getIfAvailable(), true),
                    () -> toggleListenerContainers(listenerRegistryProvider.getIfAvailable(), false));
        } else {
            log.info("[WritebackConsumer] AK-1c 观测面注册跳过（EventConsumerRegistry 缺省）");
        }
        return listener;
    }

    /**
     * AK-1c：按队列名匹配监听容器启停（endpoint registry 缺省=无操作安全回退；
     * getQueueNames 声明在 AbstractMessageListenerContainer，接口本体无常量池实证——
     * withEf 同族 API 存在性预检，instanceof 转型后调用）。
     */
    private static void toggleListenerContainers(RabbitListenerEndpointRegistry registry, boolean start) {
        if (registry == null) {
            log.info("[WritebackConsumer] AK-1c registry 启停协调跳过（endpoint registry 缺省）: start={}", start);
            return;
        }
        registry.getListenerContainers().forEach(container -> {
            if (container instanceof AbstractMessageListenerContainer amlc
                    && Arrays.asList(amlc.getQueueNames()).contains(QUEUE)) {
                if (start) {
                    amlc.start();
                } else {
                    amlc.stop();
                }
                log.info("[WritebackConsumer] AK-1c rabbit 容器{}: queue={}", start ? "启动" : "停止", QUEUE);
            }
        });
    }

    /** AZ-2：灰度暂停标记异常——SimpleRetryPolicy 分类为不可重试（defaultValue=true 下
     * 唯一 false 项），由 WritebackMessageRecoverer 分流为 requeue（禁入 DLQ）。 */
    static class WritebackPausedException extends RuntimeException {
        WritebackPausedException(String message) {
            super(message);
        }
    }

    /** AZ-1/AZ-2：恢复器分流——重试耗尽后按终因分派：WritebackPausedException →
     * 节流 1s 后抛 AmqpException（容器默认 requeue=true，消息回主队列，禁入 DLQ）；
     * 其余（真失败/不可解析）→ 委派 RepublishMessageRecoverer 重发布到 DLX/DLQ
     * （携带 x-exception-* 头留痕，原消息 ACK）。 */
    @Slf4j
    static class WritebackMessageRecoverer implements MessageRecoverer {
        private final RepublishMessageRecoverer delegate;

        WritebackMessageRecoverer(RabbitTemplate rabbitTemplate) {
            this.delegate = new RepublishMessageRecoverer(rabbitTemplate, DLX, DLQ);
        }

        @Override
        public void recover(Message message, Throwable cause) {
            Throwable root = cause;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            if (root instanceof WritebackPausedException) {
                try {
                    Thread.sleep(1000L); // requeue 热循环节流（暂停态 1 次/秒，WARN 可见）
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                throw new AmqpException("writeback consumer paused (requeue)");
            }
            delegate.recover(message, cause);
        }
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

        @RabbitListener(queues = QUEUE, containerFactory = "rabbitWritebackContainerFactory")
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
            // 灰度门（与 WritebackEventConsumer.GRAY_KEY 同键）：关闭=暂停消费。主语义=
            // AK-1c 容器 stop（消息留主队列，恢复自动续消）；本兜底仅在容器未停窗口到达——
            // AZ-2：抛 WritebackPausedException（分类为不可重试）→ WritebackMessageRecoverer
            // 节流 1s 后 requeue 回主队列（原语义 reject 入 DLQ=暂停期间事件滞死信且恢复后
            // 不回放=应急开关变数据丢失开关，禁再犯）；requeue 循环 1 次/秒 WARN 可见。
            if (!gray.enabled(WritebackEventConsumer.GRAY_KEY)) {
                log.warn("[WritebackConsumer] 灰度暂停（{}=false），requeue 回主队列", WritebackEventConsumer.GRAY_KEY);
                throw new WritebackPausedException("writeback consumer paused by gray switch");
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
                log.info("[WritebackConsumer] rabbit 通道事件已消费: itineraryId={}, eventId={}", itineraryId, envelope.eventId());
            } catch (Exception e) {
                // AZ-1：业务异常经容器工厂 spring-retry 重试（缺省 3 次、1s×2 倍退避、上限 10s），
                // 耗尽后由 WritebackMessageRecoverer 委派 RepublishMessageRecoverer republish 入
                // DLX/DLQ（原消息 ACK）。取舍披露=P0-AZ⑴：拦截器统一分类，AmqpReject 同样参与重试。
                log.error("[WritebackDLQ] rabbit 通道消费失败（重试耗尽后 republish 入死信）: itineraryId={}, error={}",
                        itineraryId, e.getMessage());
                throw new AmqpRejectAndDontRequeueException("writeback processing failed", e);
            }
        }
    }
}
