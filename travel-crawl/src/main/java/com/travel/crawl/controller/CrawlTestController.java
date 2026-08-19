package com.travel.crawl.controller;

import com.travel.crawl.common.R;
import com.travel.crawl.service.CrawlService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部测试接口（F104 2.8）：仅 local/test 且开关开启时注册；循环执行"分批轮转全量"。
 */
@RestController
@RequestMapping("/api/v1/crawl")
@RequiredArgsConstructor
@Profile({"local", "test"})
@ConditionalOnProperty(name = "travel.crawl.test-endpoint-enabled", havingValue = "true")
public class CrawlTestController {

    private final CrawlService crawlService;

    public record RotateRequest(Integer rounds, Integer citiesPerRound, String city,
                                Integer pageLimit, Boolean dryRun) {
    }

    @PostMapping("/test/rotate")
    public R<Object> rotate(@RequestBody RotateRequest req) {
        R<Object> invalid = validate(req);
        if (invalid != null) {
            return invalid;
        }
        return R.ok(crawlService.testRotate(
                req == null || req.rounds() == null ? 3 : req.rounds(),
                req == null ? null : req.citiesPerRound(),
                req == null ? null : req.city(),
                req == null ? null : req.pageLimit(),
                req == null ? null : req.dryRun()));
    }

    /** F104 2.8：rounds/citiesPerRound/pageLimit 越界 → 400（40001） */
    private R<Object> validate(RotateRequest req) {
        if (req == null) {
            return R.fail(40001, "请求体不能为空");
        }
        if (req.rounds() != null && (req.rounds() <= 0 || req.rounds() > 100)) {
            return R.fail(40001, "rounds 必须在 1~100 之间");
        }
        if (req.citiesPerRound() != null
                && (req.citiesPerRound() <= 0 || req.citiesPerRound() > 50)) {
            return R.fail(40001, "citiesPerRound 必须在 1~50 之间");
        }
        if (req.pageLimit() != null && (req.pageLimit() <= 0 || req.pageLimit() > 20)) {
            return R.fail(40001, "pageLimit 必须在 1~20 之间");
        }
        return null;
    }
}
