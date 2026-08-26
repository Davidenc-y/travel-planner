package com.travel.planning.cancellation;

/**
 * M6-44：轮次取消广播（跨实例推送加速）。
 *
 * <p>本地 registry.cancel 与 Redis 中断标记（权威）已同步完成后，广播仅用于
 * 让其它实例尽快 dispose 在途流；消息丢失不影响正确性（节点边界 Redis 标记
 * 兜底）。</p>
 */
public interface TurnCancellationBroadcaster {

    /**
     * 发布取消信号。
     *
     * @param sessionId        归属会话
     * @param clientMessageId  目标轮次幂等键
     */
    void publishCancel(String sessionId, String clientMessageId);
}
