package com.travel.crawl.detail;

import com.travel.crawl.model.AttractionRaw;

/**
 * 详情补充源 SPI（F115 T1）：按景点条目补充缺失字段（description/lat/lng 等）。
 * 实现类必须：实现 name/enabled/enrich；失败降级返回 empty（不向上抛异常）。
 */
public interface DetailSource {

    /** 数据源标识（wikidata / html-site ...） */
    String name();

    boolean enabled();

    /** 对单条景点执行补充；无可用结果返回 {@link DetailEnrichment#empty()} */
    DetailEnrichment enrich(AttractionRaw item);

    /**
     * 本次调用是否可能发起网络请求（F115 预算语义：缓存命中不算真实网络调用）。
     * 默认 true；带缓存的实现按命中情况覆写。
     */
    default boolean mayRequireNetwork(AttractionRaw item) {
        return true;
    }
}
