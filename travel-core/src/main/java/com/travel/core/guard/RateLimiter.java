package com.travel.core.guard;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
    // M6-55/T3：定时清理守护线程（每分钟移除非当前分钟窗口），
    // 防止长期运行 key 空间无界增长（阈值快速路径仍保留）
    private final ScheduledExecutorService cleaner =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "rate-limiter-cleaner");
                t.setDaemon(true);
                return t;
            });

    public RateLimiter(int perMinute) {
        this.perMinute = Math.max(1, perMinute);
        cleaner.scheduleWithFixedDelay(this::cleanupExpired,
                60, 60, TimeUnit.SECONDS);
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

    /** M6-55/T3：移除所有非当前分钟的过期窗口（供定时任务与测试调用）。 */
    void cleanupExpired() {
        cleanupExpired(System.currentTimeMillis());
    }

    void cleanupExpired(long now) {
        long minute = now / 60_000L;
        windows.entrySet().removeIf(e -> e.getValue().minute() != minute);
    }

    /** 释放清理线程（应用关闭时调用；不调用也无碍，daemon 线程随 JVM 退出）。 */
    public void close() {
        cleaner.shutdownNow();
    }

    /** 当前窗口计数（观测用） */
    public int countOf(String key) {
        Window w = windows.get(key);
        return w == null ? 0 : w.count.get();
    }

    private record Window(long minute, AtomicInteger count) {
    }
}
