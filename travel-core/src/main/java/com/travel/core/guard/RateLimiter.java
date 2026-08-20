package com.travel.core.guard;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 简单固定窗口限流器（F90 下沉至 travel-core，F110-B）。
 *
 * <p>按 key（如 userId:接口）在每分钟窗口内允许 perMinute 次；窗口滑动后重置。
 * 线程安全、无外部依赖，供 planning/knowledge/crawl 复用，未来可替换为
 * Guava/RateLimiter 或 Redis 分布式实现（接口不变）。</p>
 */
@Slf4j
public class RateLimiter {

    private final int perMinute;
    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimiter(int perMinute) {
        this.perMinute = Math.max(1, perMinute);
    }

    /**
     * 尝试获取一次许可。
     *
     * @return true=放行；false=超限
     */
    public boolean tryAcquire(String key) {
        long now = System.currentTimeMillis();
        long minute = now / 60_000L;
        Window w = windows.compute(key, (k, old) -> {
            if (old == null || old.minute != minute) {
                return new Window(minute, new AtomicInteger(0));
            }
            return old;
        });
        // M3-8/P2-17：窗口数超过阈值时清理过期窗口，防内存泄漏
        if (windows.size() > 4096) {
            windows.entrySet().removeIf(e -> e.getValue().minute() != minute);
        }
        int count = w.count.incrementAndGet();
        return count <= perMinute;
    }

    /** 当前窗口计数（观测用） */
    public int countOf(String key) {
        Window w = windows.get(key);
        return w == null ? 0 : w.count.get();
    }

    private record Window(long minute, AtomicInteger count) {
    }
}
