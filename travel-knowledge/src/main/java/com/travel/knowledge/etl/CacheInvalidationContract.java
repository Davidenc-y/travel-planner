package com.travel.knowledge.etl;

/**
 * AP-5 跨任务契约常量（甲侧声明）：city-counts 缓存失效广播。
 *
 * <p>契约单源=docs/zcode/20261002_review/01-AP系列调度短路定位与Tavily配额补齐实施方案.md §〇
 * （P0㉖ 契约冻结：频道与 payload 逐字，禁自创字段）；乙侧 planning AP-B2 独立声明同契约，
 * 审计窗合线实测（导入→即时失效→翻转）。</p>
 */
public final class CacheInvalidationContract {

    /** 失效广播频道（逐字冻结） */
    public static final String CHANNEL_CITY_COUNTS = "travel:cache:invalidate";

    /** 失效广播 payload（单行 JSON，逐字冻结，禁自创字段） */
    public static final String PAYLOAD_CITY_COUNTS = "{\"type\":\"cityCounts\"}";

    private CacheInvalidationContract() {
    }
}
