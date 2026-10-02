package com.travel.planning.memory.pipeline;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * AP-B2（GP-5）：city-counts 缓存失效广播监听容器——{@code ChatCancellationPubSubConfig}
 * 生命周期同款先例（容器手动 new+显式 afterPropertiesSet+SmartLifecycle 异步启动与
 * 固定间隔重试，Redis 不可用不阻塞应用启动；开关关闭时不创建容器）。
 *
 * <p><b>P0㉗ 契约冻结（方案 §〇 单源）</b>：频道 {@code travel:cache:invalidate}、
 * payload {@code {"type":"cityCounts"}}（JSON 单行）——两常量<b>乙侧独立声明逐字写死</b>
 * （禁 yml 可配防漂移、禁从 knowledge/common 引用），knowledge 发布侧（AP-A5a）各自
 * 声明同值常量，审计窗合线做两侧一致静态断言。</p>
 *
 * <p>E-33/行为可回退：开关 {@code travel.rag.city-corpus-invalidation.enabled} 默认
 * false（键在码零 yml，方案未点名键按铁律 7 补默认关——默认关=行为零变化，开启归
 * 审计窗 AP-5 合线实测）。</p>
 */
@Configuration
public class CityCorpusInvalidationPubSubConfig {

    /** AP-5 契约：失效广播频道名（逐字，与 knowledge 发布侧一致；P0㉗）。 */
    public static final String CHANNEL_CACHE_INVALIDATE = "travel:cache:invalidate";

    /** AP-5 契约：city-counts 失效事件 type 值（逐字，payload JSON 单行 {"type":"cityCounts"}；P0㉗）。 */
    public static final String TYPE_CITY_COUNTS = "cityCounts";

    @Bean
    @ConditionalOnProperty(prefix = "travel.rag.city-corpus-invalidation",
            name = "enabled", havingValue = "true", matchIfMissing = false)
    public CityCorpusInvalidationListenerLifecycle cityCorpusInvalidationListenerLifecycle(
            RedisConnectionFactory connectionFactory,
            CityCorpusInvalidationSubscriber subscriber) throws Exception {
        return new CityCorpusInvalidationListenerLifecycle(
                buildContainer(connectionFactory, subscriber));
    }

    RedisMessageListenerContainer buildContainer(
            RedisConnectionFactory connectionFactory,
            CityCorpusInvalidationSubscriber subscriber) throws Exception {
        RedisMessageListenerContainer container = createContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(CHANNEL_CACHE_INVALIDATE));
        container.afterPropertiesSet();
        return container;
    }

    /** 容器创建钩子（测试可覆写以断言 afterPropertiesSet 已调用，取消先例同款）。 */
    RedisMessageListenerContainer createContainer() {
        return new RedisMessageListenerContainer();
    }
}
