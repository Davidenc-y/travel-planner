package com.travel.planning.cancellation;

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
            ChatCancellationPubSubProperties props) throws Exception {
        RedisMessageListenerContainer container =
                buildContainer(connectionFactory, subscriber, props.getChannel());
        return new ChatCancellationListenerLifecycle(container);
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
