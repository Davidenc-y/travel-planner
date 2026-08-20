package com.travel.crawl.util;

import lombok.extern.slf4j.Slf4j;

import java.time.YearMonth;
import java.util.concurrent.atomic.AtomicInteger;

/** 月度免费配额保护（F104 2.5）：5000/月，85% 告警、100% 停、次月重置。 */
@Slf4j
public class MonthlyQuotaGuard implements QuotaGuard {

    private final int quota;
    private final double warnRatio;
    private final AtomicInteger used = new AtomicInteger();
    private volatile YearMonth month = YearMonth.now();
    private volatile boolean warned = false;

    public MonthlyQuotaGuard(int quota, double warnRatio) {
        this.quota = Math.max(1, quota);
        this.warnRatio = warnRatio;
    }

    public synchronized boolean tryAcquire() {
        YearMonth now = YearMonth.now();
        if (!now.equals(month)) {
            month = now;
            used.set(0);
            warned = false;
            log.info("[Quota] 月度配额已重置: {}", now);
        }
        int next = used.incrementAndGet();
        if (next > quota) {
            used.decrementAndGet();
            return false;
        }
        if (!warned && next >= (int) (quota * warnRatio)) {
            warned = true;
            log.warn("[Quota] 月度配额已使用 {}%（{}/{}），请关注免费额度", (int) (warnRatio * 100), next, quota);
        }
        return true;
    }

    public int used() {
        return used.get();
    }

    public int limit() {
        return quota;
    }
}
