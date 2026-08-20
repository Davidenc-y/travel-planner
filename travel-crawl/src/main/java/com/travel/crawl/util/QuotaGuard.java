package com.travel.crawl.util;

/**
 * 配额守卫端口（F110-B）：本地内存实现（重启归零）或 Redis 持久实现（重启/多实例不归零）。
 */
public interface QuotaGuard {

    /** 尝试获取一次配额；false=已超限（调用方停止抓取） */
    boolean tryAcquire();

    int used();

    int limit();
}
