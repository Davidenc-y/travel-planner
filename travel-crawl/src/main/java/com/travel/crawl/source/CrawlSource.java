package com.travel.crawl.source;

import com.travel.crawl.model.AttractionRaw;

import java.util.List;

/**
 * 抓取数据源 SPI（F110-B）：新增数据源 = 实现本接口 + 注册 Bean + 配置开关，
 * 主流程（调度/管道/队列/发布）不变。
 */
public interface CrawlSource {

    /** 数据源标识（amap-text / amap-around / mock ...） */
    String name();

    boolean enabled();

    /**
     * 抓取一页数据。
     *
     * @return 解析后的条目；空表示该页无数据（调用方决定是否回退其他源）
     */
    List<AttractionRaw> fetch(CrawlQuery query, int pageNum);
}
