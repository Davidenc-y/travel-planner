package com.travel.crawl.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.YearMonth;

/**
 * Redis 持久化月度配额（F110-B）：重启/多实例不归零；quota-type=redis 时启用。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "travel.crawl.quota-type", havingValue = "redis")
public class RedisMonthlyQuotaGuard implements QuotaGuard {

    private final StringRedisTemplate redis;
    private final int quota;
    private final double warnRatio;

    public RedisMonthlyQuotaGuard(StringRedisTemplate redis,
                                  com.travel.crawl.config.CrawlProperties props) {
        this.redis = redis;
        this.quota = Math.max(1, props.getMonthlyQuota());
        this.warnRatio = props.getMonthlyWarnRatio();
    }

    @Override
    public boolean tryAcquire() {
        String key = key();
        Long v = redis.opsForValue().increment(key);
        if (v != null && v == 1) {
            redis.expire(key, Duration.ofDays(32));
        }
        if (v != null && v > quota) {
            redis.opsForValue().decrement(key);
            return false;
        }
        if (v != null && !warned(key) && v >= (int) (quota * warnRatio)) {
            Boolean first = redis.opsForValue().setIfAbsent(key + ":warned", "1", Duration.ofDays(32));
            if (Boolean.TRUE.equals(first)) {
                log.warn("[Quota] 月度配额已使用 {}%（{}/{}），请关注免费额度",
                        (int) (warnRatio * 100), v, quota);
            }
        }
        return true;
    }

    @Override
    public int used() {
        String v = redis.opsForValue().get(key());
        return v == null ? 0 : Integer.parseInt(v);
    }

    @Override
    public int limit() {
        return quota;
    }

    private String key() {
        return "crawl:quota:amap:" + YearMonth.now();
    }

    private boolean warned(String key) {
        return Boolean.TRUE.equals(redis.hasKey(key + ":warned"));
    }
}
