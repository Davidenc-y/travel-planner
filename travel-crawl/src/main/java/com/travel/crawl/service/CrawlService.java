package com.travel.crawl.service;

import java.util.Map;

/**
 * CrawlService 公共契约（AD-2e 接口化）。
 *
 * <p>实现见 {@link CrawlServiceImpl}（@Service 在 Impl）；
 * 公有方法签名逐字提取（零语义变更）。</p>
 */
public interface CrawlService {

    /** 执行一轮采集（城市覆盖参可选）。 */
    Map<String, Object> runRound(String cityOverride);

    /** 试轮换（手动触发指定轮数/城市/分页/干跑）。 */
    Map<String, Object> testRotate(int rounds, Integer citiesPerRound, String city,
                                   Integer pageLimit, Boolean dryRun);

    /** 采集状态查询。 */
    Map<String, Object> status(String state);

    /** 手动导入单条原始景点。 */
    Map<String, Object> importManual(com.travel.crawl.model.AttractionRaw item);
}
