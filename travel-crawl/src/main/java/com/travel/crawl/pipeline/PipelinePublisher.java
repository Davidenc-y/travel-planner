package com.travel.crawl.pipeline;

import java.nio.file.Path;

/** 导入流水线发布（F104 2.4：Phase 2 可换 RabbitMQ 实现，业务不变）。 */
public interface PipelinePublisher {

    /** 导入结果（ok=是否成功；inserted/updated/skipped 来自 knowledge X-Import-Stats） */
    record PipelineResult(boolean ok, int inserted, int updated, int skipped) {
    }

    /** 发布一个待处理文件给导入+向量化；返回是否成功 */
    PipelineResult publish(Path file);
}
