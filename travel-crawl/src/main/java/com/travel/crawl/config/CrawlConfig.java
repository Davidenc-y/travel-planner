package com.travel.crawl.config;

import com.travel.crawl.store.CrawlQueue;
import com.travel.crawl.store.LocalCrawlFileStore;
import com.travel.crawl.store.LocalCrawlQueue;
import com.travel.crawl.util.MonthlyQuotaGuard;
import com.travel.crawl.util.QuotaGuard;
import com.travel.crawl.util.RequestThrottle;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 爬虫基础组件装配（F110-B）：节流器 + 配额守卫（接口）+ 本地执行队列 */
@Configuration
public class CrawlConfig {

    @Bean
    public RequestThrottle requestThrottle(CrawlProperties props) {
        return new RequestThrottle(props.getMinIntervalMs(),
                props.getJitterMinMs(), props.getJitterMaxMs());
    }

    @Bean
    @ConditionalOnProperty(name = "travel.crawl.quota-type", havingValue = "local",
            matchIfMissing = true)
    public QuotaGuard quotaGuard(CrawlProperties props) {
        return new MonthlyQuotaGuard(props.getMonthlyQuota(), props.getMonthlyWarnRatio());
    }

    @Bean
    @ConditionalOnProperty(name = "travel.crawl.queue-type", havingValue = "local",
            matchIfMissing = true)
    public CrawlQueue localCrawlQueue(LocalCrawlFileStore fileStore) {
        return new LocalCrawlQueue(fileStore);
    }
}
