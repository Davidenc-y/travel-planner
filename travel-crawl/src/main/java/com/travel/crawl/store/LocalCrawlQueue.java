package com.travel.crawl.store;

import com.travel.crawl.model.AttractionRaw;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地执行队列（F110-B 默认）：复用 0/1 文件队列（append=入队，readPending=drain，
 * markDone=ack），保持单实例语义与归档能力。
 */
@Slf4j
public class LocalCrawlQueue implements CrawlQueue {

    private final LocalCrawlFileStore fileStore;

    public LocalCrawlQueue(LocalCrawlFileStore fileStore) {
        this.fileStore = fileStore;
    }

    @Override
    public void enqueue(List<AttractionRaw> items) {
        try {
            fileStore.append(items);
        } catch (IOException e) {
            log.warn("[CrawlQueue] 入队（文件追加）失败: {}", e.getMessage());
        }
    }

    @Override
    public List<CrawlBatch> drain(int maxBatches) {
        try {
            List<CrawlBatch> batches = new ArrayList<>();
            for (CrawlFileStore.PendingFile pf : fileStore.readPending()) {
                if (batches.size() >= maxBatches) {
                    break;
                }
                batches.add(new CrawlBatch(pf.file().toString(), pf.items()));
            }
            return batches;
        } catch (IOException e) {
            log.warn("[CrawlQueue] drain 失败: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public void ack(String ref) {
        try {
            fileStore.markDone(Path.of(ref));
        } catch (IOException e) {
            log.warn("[CrawlQueue] ack（0→1）失败: {}", e.getMessage());
        }
    }

    @Override
    public int pendingCount() {
        try {
            return fileStore.readPending().size();
        } catch (IOException e) {
            return 0;
        }
    }
}
