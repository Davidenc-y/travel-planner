package com.travel.crawl.store;

import com.travel.crawl.model.AttractionRaw;

import java.util.List;

/**
 * 爬虫执行队列（F110-B）：local=进程内+0/1 文件归档；redis=Redis Stream（多实例安全）。
 */
public interface CrawlQueue {

    /** 入队（自动归一化去重） */
    void enqueue(List<AttractionRaw> items);

    /** 拉取最多 maxBatches 个批次（不删除） */
    List<CrawlBatch> drain(int maxBatches);

    /** 确认消费成功（local=文件 0→1；redis=XACK） */
    void ack(String ref);

    int pendingCount();

    record CrawlBatch(String ref, List<AttractionRaw> items) {
    }
}
