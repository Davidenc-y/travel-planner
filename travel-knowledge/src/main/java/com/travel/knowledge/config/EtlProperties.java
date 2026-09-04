package com.travel.knowledge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * ETL 并行化配置（F119）：travel.etl.*。
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.etl")
public class EtlProperties {

    /** 每批未索引扫描上限（默认 100，与 yml travel.etl.batch-size 对齐） */
    private int batchSize = 100;

    /** ETL 并行度（默认 4，范围 1~8） */
    private int parallelism = 4;

    /** DashScope Embedding 每秒调用上限（默认 4 QPS；并行首要在并行度，此处做全局节流） */
    private int embeddingQps = 4;

    /** 单条 ETL 限流等待/执行兜底（毫秒，默认 60s） */
    private long timeoutMs = 60_000L;
}
