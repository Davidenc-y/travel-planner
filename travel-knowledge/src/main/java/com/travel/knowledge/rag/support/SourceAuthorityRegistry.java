package com.travel.knowledge.rag.support;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MR-E1：来源权威度注册表（对应 yml {@code travel.rag.source-registry.<source>=<authority>}）。
 *
 * <p>冲突治理裁决链的检索期权重面（文章 §13.3）：source → authority（0~1）。
 * <b>默认空注册表=不参与排序</b>（E-33：RagFilterBuilder 查询结构零变更）；
 * 注册后按 source 追加 ES should 加权分量（authority 即 term boost）。</p>
 *
 * <p>数据前提（见《冲突治理字段缺口报告》20260918）：t_attraction 已有 source 字段
 * （M8-5），但 ES/Milvus 检索文档尚未携带该字段——分量实际命中需重灌索引（数据操作，
 * P2 人工裁决，非 DDL）。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.rag.source-registry")
public class SourceAuthorityRegistry {

    /** source → authority（0~1；默认空=不参与排序） */
    private Map<String, Double> registry = new LinkedHashMap<>();

    /** 注册表非空才参与排序（E-33 关闭态判定） */
    public boolean isActive() {
        return registry != null && !registry.isEmpty();
    }
}
