package com.travel.planning.trace;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * T-3a：TTFT requestId 键控旁路通道。
 *
 * <p>背景：S-B7 经 TraceContext ThreadLocal holder 写 ttftMs，执行/回调线程与
 * TraceAspect begin 线程不一致时 {@code active=false} 静默跳过（chat 行 ttft 3/14
 * 非空的疑似根因）。本通道复刻 {@link ModelRouteTracker} record/take（取走即移除）
 * 先例：执行器无条件双写（holder 保留 + 通道兜底），AgentTraceCollector.end 组装时
 * holder 优先、通道兜底。E-48 合规：requestId 键控 ConcurrentHashMap，非 ThreadLocal。</p>
 */
public final class TtftChannel {

    /** 安全阀：超过即整清（trace 关闭/异常路径下未消费条目的泄漏防护；粗粒度取舍） */
    static final int MAX_ENTRIES = 1024;

    private static final ConcurrentMap<String, Long> TTFTS = new ConcurrentHashMap<>();

    private TtftChannel() {
    }

    public static void record(String requestId, long ttftMs) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        if (TTFTS.size() > MAX_ENTRIES) {
            TTFTS.clear();
        }
        TTFTS.put(requestId, ttftMs);
    }

    /** 取走即移除（无则 null）；仅 AgentTraceCollector.end 消费。 */
    public static Long take(String requestId) {
        if (requestId == null) {
            return null;
        }
        return TTFTS.remove(requestId);
    }
}
