package com.travel.common.event;

/**
 * Y-3a：事件总线端口（writeback 试点，Y-3 线双通道抽象）。
 *
 * <p>redis=现状字节等价实现（{@link RedisStreamEventBus}，XADD 字段面不变）；
 * rabbit=Y-3b 装配（spring-cloud-stream StreamBridge）；{@code travel.eventbus.type}
 * 路由切换归 Y-3c。实现须保证发布失败返回 false（不抛出），由调用方回退同步执行。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
public interface EventBusPort {

    /**
     * 发布事件信封。
     *
     * @return true=已投递到传输通道；false=发布失败（调用方回退同步执行）
     */
    boolean publish(EventEnvelope envelope);
}
