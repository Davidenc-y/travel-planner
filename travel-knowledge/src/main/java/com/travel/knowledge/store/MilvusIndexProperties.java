package com.travel.knowledge.store;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MR-D3：Milvus 索引参数显式配置（对应 yml {@code travel.knowledge.milvus.index.*}）。
 *
 * <p>默认值 = 2026-09-18 DESCRIBE INDEX 实测隐式现值（session_context/attraction_vectors
 * 两集合均 <b>IVF_FLAT / L2 / nlist=1024</b>）——显式化不改变行为（E-33）。
 * 文章 §6.3 HNSW 建议区间 M16-32 / efConstruction 100-200 / ef 50-200、§6.5 SQ8≈内存 1/4：
 * 切换 index-type 属行为变更（需重建索引+人工裁决），本批不启用。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.knowledge.milvus.index")
public class MilvusIndexProperties {

    /** 索引类型（io.milvus.param.IndexType 枚举名；现值 IVF_FLAT） */
    private String indexType = "IVF_FLAT";

    /** IVF 类索引聚类中心数 nlist（现值 1024） */
    private int nlist = 1024;

    /** 度量类型（io.milvus.param.MetricType 枚举名；现值 L2） */
    private String metric = "L2";
}
