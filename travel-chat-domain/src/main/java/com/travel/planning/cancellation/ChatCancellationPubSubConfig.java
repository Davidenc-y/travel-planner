package com.travel.planning.cancellation;

import com.travel.common.event.EventConsumerRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * M6-44：取消广播监听容器。
 *
 * <p>8081/8083 各自 JVM 注册一个容器订阅同一频道；收到广播后仅取消本地
 * registry（幂等）。开关关闭时不创建容器。</p>
 *
 * <p>AK-1d：lifecycle 装配时挂接 {@link EventConsumerRegistry} 统一观测面
 * （registry 启停动作真实委托 {@code lifecycle::start/stop}——本消费者即
 * SmartLifecycle 生命周期体）；触发逻辑零改动=P0⑲（lifecycle 的异步启动/重试/停止
 * 路径逐字未动，Spring autoStartup 语义不变，registry 启停默认不调用）。</p>
 */
@Configuration
public class ChatCancellationPubSubConfig {

    /**
     * M6-46：接管监听容器启动（异步 + backoff 重连），Redis 不可用不阻塞应用启动。
     *
     * <p>容器不作为 Spring bean 注册（避免 LifecycleProcessor 同步 start 阻塞
     * 上下文刷新），由本 Lifecycle 内部创建并负责异步启动/重连/停止。</p>
     */
    @Bean
    @ConditionalOnProperty(prefix = "travel.chat.cancellation",
            name = "enabled", havingValue = "true", matchIfMissing = true)
    public ChatCancellationListenerLifecycle chatCancellationListenerLifecycle(
            RedisConnectionFactory connectionFactory,
            TurnCancellationSubscriber subscriber,
            ChatCancellationPubSubProperties props,
            ObjectProvider<EventConsumerRegistry> consumerRegistryProvider) throws Exception {
        RedisMessageListenerContainer container =
                buildContainer(connectionFactory, subscriber, props.getChannel());
        ChatCancellationListenerLifecycle lifecycle = new ChatCancellationListenerLifecycle(container);
        // AK-1d：观测面挂接（ObjectProvider 软化——窄测试上下文缺 bean=降级跳过，
        // 生产面 EventConsumerRegistry 经组件扫描必在）
        EventConsumerRegistry consumerRegistry = consumerRegistryProvider.getIfAvailable();
        if (consumerRegistry != null) {
            consumerRegistry.register("redis-pubsub", "chat-cancellation",
                    "travel.chat.cancellation.enabled", lifecycle::start, lifecycle::stop);
        }
        return lifecycle;
    }

    /**
     * M8-9c 修复：容器是手动 new 出来的、不作为 Spring bean 注册，
     * Spring 不会自动调用 {@code afterPropertiesSet()}，而
     * {@link RedisMessageListenerContainer#start()} 又不会代为初始化，
     * 直接启动会报 “Subscriber not created; ... afterPropertiesSet() has been called”。
     * 因此在创建后显式初始化一次。
     */
    RedisMessageListenerContainer buildContainer(
            RedisConnectionFactory connectionFactory,
            TurnCancellationSubscriber subscriber,
            String channel) throws Exception {
        RedisMessageListenerContainer container = createContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(channel));
        container.afterPropertiesSet();
        return container;
    }

    /** 容器创建钩子（测试可覆写以断言 afterPropertiesSet 已调用） */
    RedisMessageListenerContainer createContainer() {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        return container;
    }
}
