package com.travel.core.guard;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 安全限频（F104 2.2，M12-0 自 travel-crawl util 下沉）：minInterval + 随机退避，串行等待。
 *
 * <p>供 travel-crawl / travel-planning 等所有外部 HTTP 调用方复用，保证单一实现；
 * 最小间隔允许 1ms（如 500ms = 2 QPS），由调用方按配额安全值配置。</p>
 */
@Slf4j
public class RequestThrottle {

    private final long minIntervalMs;
    private final long jitterMinMs;
    private final long jitterMaxMs;
    private volatile long lastRequestAt = 0;

    public RequestThrottle(long minIntervalMs, long jitterMinMs, long jitterMaxMs) {
        this.minIntervalMs = Math.max(1, minIntervalMs);
        this.jitterMinMs = Math.max(0, jitterMinMs);
        this.jitterMaxMs = Math.max(this.jitterMinMs, jitterMaxMs);
    }

    public synchronized void waitIfNeeded() {
        long jitter = jitterMinMs + (long) (ThreadLocalRandom.current().nextDouble()
                * (jitterMaxMs - jitterMinMs));
        long gap = minIntervalMs + jitter;
        long now = System.currentTimeMillis();
        long wait = gap - (now - lastRequestAt);
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastRequestAt = System.currentTimeMillis();
    }
}
