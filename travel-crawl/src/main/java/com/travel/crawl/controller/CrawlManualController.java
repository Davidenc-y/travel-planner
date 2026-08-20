package com.travel.crawl.controller;

import com.travel.core.data.SourceConfidence;
import com.travel.crawl.common.R;
import com.travel.crawl.model.AttractionRaw;
import com.travel.crawl.service.CrawlService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 人工补录接口（F110-B Phase 3）：以 MANUAL 置信度提交字段修正，
 * 经执行队列发布导入（upsert，MANUAL 优先级最高）。仅 local/test 且开关开启时注册。
 */
@RestController
@RequestMapping("/api/v1/crawl")
@RequiredArgsConstructor
@Profile({"local", "test"})
@ConditionalOnProperty(name = "travel.crawl.manual-endpoint-enabled", havingValue = "true")
public class CrawlManualController {

    private final CrawlService crawlService;

    public record ManualRequest(String poiId, String name, String city, String type,
                                String description, Double lat, Double lng, String address,
                                String openHours, Double ticketPrice, Integer freeEntry,
                                Double rating, String tags, String imageUrl) {
    }

    @PostMapping("/items/manual")
    public R<Object> manual(@RequestBody ManualRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()
                || req.city() == null || req.city().isBlank()) {
            return R.fail(40001, "name 与 city 必填");
        }
        AttractionRaw raw = new AttractionRaw(
                req.poiId(), req.name().trim(), req.city().trim(), "",
                req.type() == null || req.type().isBlank() ? "CULTURE" : req.type(),
                req.description(), req.lat(), req.lng(), req.address(), req.openHours(),
                req.ticketPrice(), req.freeEntry(), req.rating(), null,
                req.tags() == null || req.tags().isBlank() ? "[\"景点\"]" : req.tags(),
                "", req.imageUrl(), "manual", SourceConfidence.MANUAL,
                java.util.List.<String>of(), LocalDateTime.now().toString());
        return R.ok(crawlService.importManual(raw));
    }
}
