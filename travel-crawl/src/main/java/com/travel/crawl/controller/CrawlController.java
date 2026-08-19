package com.travel.crawl.controller;

import com.travel.crawl.common.R;
import com.travel.crawl.service.CrawlService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 爬虫管理端点（F104 2.5/2.8：内部测试用，非用户面）。
 * 仅 local/test 且开关开启时注册；生产 profile 下 404。
 */
@RestController
@RequestMapping("/api/v1/crawl")
@RequiredArgsConstructor
@Profile({"local", "test"})
@ConditionalOnProperty(name = "travel.crawl.test-endpoint-enabled", havingValue = "true")
public class CrawlController {

    private final CrawlService crawlService;

    @PostMapping("/trigger")
    public R<Object> trigger() {
        return R.ok(crawlService.runRound(null));
    }

    @GetMapping("/status")
    public R<Object> status() {
        return R.ok(crawlService.status(null));
    }
}
