package com.travel.core.guard;

/**
 * 配额守卫端口（F110-B，M12-0 自 travel-crawl util 下沉）。
 *
 * <p>本地内存实现（重启归零）或 Redis 持久实现（重启/多实例不归零）；
 * travel-crawl 的 Redis 实现保留在各模块内，接口统一由 travel-core 提供。</p>
 */
public interface QuotaGuard {

    /** 尝试获取一次配额；false=已超限（调用方停止请求） */
    boolean tryAcquire();

    int used();

    int limit();
}
