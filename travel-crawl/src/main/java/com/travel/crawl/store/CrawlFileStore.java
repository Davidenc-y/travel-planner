package com.travel.crawl.store;

import com.travel.crawl.model.CrawlItem;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** 爬虫产物文件存储（F104 2.4：0/1 前缀、串行追加、读取置 1）。 */
public interface CrawlFileStore {

    /** 串行追加（按归一化去重键合并去重；存在 0_* 则追加，否则新建） */
    void append(List<CrawlItem> items) throws IOException;

    /** 读取全部待处理文件（0_*）及其内容 */
    List<PendingFile> readPending() throws IOException;

    /** 处理成功后把 0_* 重命名为 1_* */
    void markDone(Path file) throws IOException;

    List<Path> listFiles();

    record PendingFile(Path file, List<CrawlItem> items) {
    }
}
