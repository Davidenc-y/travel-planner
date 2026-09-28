package com.travel.knowledge.rag.rerank;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * M4-6：Rerank 配置（对应 yml {@code travel.rag.rerank.*}）。
 *
 * <p>默认 type=none（{@link NoopReranker} 直通），行为与现状一致，回归零风险；
 * 切换 dashscope 启用 {@link DashScopeReranker}（gte-rerank-v2）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.rag.rerank")
public class RerankProperties {

    /** rerank 实现类型：none（默认，直通）/ dashscope */
    private String type = "none";

    /** 精排前内部候选池大小：检索量放大到 max(topK, candidate-pool)，由 rerank 截回 topK */
    private int candidatePool = 20;

    /** DashScope rerank 单次调用硬性超时（毫秒），超时 fail-open */
    private long timeoutMs = 2000;

    /** MR-B1：rerank 低置信阈值门控（E-33 默认 0.0=关闭；(0,1] 生效，topScore&lt;threshold 判低置信） */
    private double gateThreshold = 0.0;

    /** AF-1a：hard 级阈值（0.0=关闭 hard 标记，E-33；初值建议 0.02 由 Nacos 审计实弹窗配置，代码默认关闭） */
    private double gateHardThreshold = 0.0;

    /** AG-2a：hard 段逐条模式（默认 false=组级现状逐字保留，E-33；true=组内逐条 score&lt;hard 判定标记，G2 修复） */
    private boolean gateHardPerItem = false;
}
