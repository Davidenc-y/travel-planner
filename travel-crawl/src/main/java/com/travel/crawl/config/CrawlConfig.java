package com.travel.crawl.config;

import com.travel.crawl.util.MonthlyQuotaGuard;
import com.travel.crawl.util.RequestThrottle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 爬虫基础组件装配：节流器 + 月度配额守卫 */
@Configuration
public class CrawlConfig {

    @Bean
    public RequestThrottle requestThrottle(CrawlProperties props) {
        return new RequestThrottle(props.getMinIntervalMs(),
                props.getJitterMinMs(), props.getJitterMaxMs());
    }

    @Bean
    public MonthlyQuotaGuard monthlyQuotaGuard(CrawlProperties props) {
        return new MonthlyQuotaGuard(props.getMonthlyQuota(), props.getMonthlyWarnRatio());
    }
}
