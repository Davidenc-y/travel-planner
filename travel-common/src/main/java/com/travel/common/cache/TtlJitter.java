package com.travel.common.cache;

import java.util.concurrent.ThreadLocalRandom;

/**
 * BB-4：缓存 TTL 随机化工具（雪崩防护）——批量同时写入的缓存加 ±20% 抖动，
 * 避免同批过期引发雪崩式击穿。纯静态工具零依赖。
 */
public final class TtlJitter {

    private TtlJitter() {
    }

    /** 在基准 TTL 上加 [0, base*0.2] 的随机抖动（恒正偏移——只增不减，不缩短有效窗口）。 */
    public static long jitter(long baseMillis) {
        if (baseMillis <= 0) {
            return baseMillis;
        }
        return baseMillis + ThreadLocalRandom.current().nextLong(0, Math.max(1, baseMillis / 5));
    }

    /** Duration 版。 */
    public static java.time.Duration jitter(java.time.Duration base) {
        return java.time.Duration.ofMillis(jitter(base.toMillis()));
    }
}
