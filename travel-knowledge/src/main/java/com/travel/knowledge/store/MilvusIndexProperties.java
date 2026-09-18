package com.travel.knowledge.store;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MR-D3/MR2-1：Milvus 索引参数显式配置（对应 yml {@code travel.knowledge.milvus.index.*}）。
 *
 * <p>MR2-1（2026-09-18 二次审批同步）：两集合物理索引已从 IVF_FLAT 切换为
 * <b>HNSW / M=16 / efConstruction=128 / L2</b>，本类字段同步支持 HNSW 参数
 * （新增 M/efConstruction 字段，nlist 保留兼容 IVF 回退场景）。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.knowledge.milvus.index")
public class MilvusIndexProperties {

    /** 索引类型（io.milvus.param.IndexType 枚举名；MR2-1 起现值 HNSW） */
    private String indexType = "HNSW";

    /** IVF 类索引聚类中心数 nlist（IVF_FLAT 回退场景用；HNSW 不使用） */
    private int nlist = 1024;

    /** HNSW 每节点最大邻居数 M（文章 §6.3 建议 16~32；MR2-1 起现值 16） */
    private int M = 16;

    /** HNSW 建图时候选集大小 efConstruction（文章 §6.3 建议 100~200；MR2-1 起现值 128） */
    private int efConstruction = 128;

    /** 度量类型（io.milvus.param.MetricType 枚举名；现值 L2） */
    private String metric = "L2";
}
