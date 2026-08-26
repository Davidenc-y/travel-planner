package com.travel.planning.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * M6-40：在途轮次取消登记表（内存，key=clientMessageId，UUID 全局唯一）。
 *
 * <p>本地低延迟取消；跨实例由 Redis 中断标记兜底（chat:interrupt:*）。
 * 生命周期：ChatService.runStream 开始 register、结束 remove；
 * 停止/新消息/断连调用 cancel。</p>
 */
@Component
public class TurnCancellationRegistry {

    private final ConcurrentMap<String, TurnCancellation> cancellations = new ConcurrentHashMap<>();

    public TurnCancellation register(String clientMessageId) {
        if (clientMessageId == null || clientMessageId.isBlank()) {
            return TurnCancellation.NOOP;
        }
        // M6-42：令牌携带 key，供图内 TokenUsageInterceptor 从 RunnableConfig metadata 取 key 查登记
        return cancellations.computeIfAbsent(clientMessageId, TurnCancellation::new);
    }

    public TurnCancellation get(String clientMessageId) {
        return cancellations.get(clientMessageId);
    }

    /** 取消并立即 dispose 在途订阅；返回是否存在该轮次 */
    public boolean cancel(String clientMessageId) {
        if (clientMessageId == null || clientMessageId.isBlank()) {
            return false;
        }
        TurnCancellation c = cancellations.get(clientMessageId);
        return c != null && c.cancel();
    }

    public void remove(String clientMessageId) {
        if (clientMessageId != null) {
            cancellations.remove(clientMessageId);
        }
    }
}
