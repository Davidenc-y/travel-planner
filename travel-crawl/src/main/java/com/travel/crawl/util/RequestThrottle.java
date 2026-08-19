package com.travel.crawl.util;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ThreadLocalRandom;

/** 安全限频（F104 2.2）：minInterval + 随机退避，串行等待。 */
@Slf4j
public class RequestThrottle {

    private final long minIntervalMs;
    private final long jitterMinMs;
    private final long jitterMaxMs;
    private volatile long lastRequestAt = 0;

    public RequestThrottle(long minIntervalMs, long jitterMinMs, long jitterMaxMs) {
        this.minIntervalMs = Math.max(1000, minIntervalMs);
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
