package com.travel.crawl.schedule;

import com.travel.crawl.config.CrawlProperties;
import com.travel.crawl.service.CrawlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 爬虫定时任务（F104 2.5）：每小时一轮，可配 cron；防重入由 CrawlService 的 running 保证。
 * 每轮 2 城市 × 最多 5 请求，337 城市约 169 小时覆盖一轮全量（与 full-refresh-interval-hours=168 对齐）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrawlScheduler {

    private final CrawlService crawlService;
    private final CrawlProperties props;

    @Scheduled(cron = "${travel.crawl.schedule-cron:0 0 * * * ?}")
    public void scheduledRound() {
        if (!props.isEnabled()) {
            log.info("[Crawl] 定时任务跳过：travel.crawl.enabled=false");
            return;
        }
        try {
            Map<String, Object> result = crawlService.runRound(null);
            log.info("[Crawl] 定时轮完成: {}", result);
        } catch (Exception e) {
            log.warn("[Crawl] 定时轮执行异常（下一轮自动重试）: {}", e.getMessage());
        }
    }
}
