package com.travel.knowledge.rag.quality;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * M8-9d：候选质量感知选择配置（对应 yml {@code travel.rag.quality.*}）。
 *
 * <p>默认关闭（enabled=false）→ 模板出口顺序截断，行为与现状逐字节一致；
 * 灰度开启后放大召回池并按「融合分 × (1 + α×质量分)」截断 topK。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.rag.quality")
public class QualityProperties {

    /** 总开关（默认 false，回滚零风险） */
    private boolean enabled = false;

    /** 召回放大池：检索量放大到 max(topK, candidatePool)，由质量截断收回 topK */
    private int candidatePool = 20;

    /** 质量分加权系数 α：最终分 = 融合分 × (1 + α×质量分) */
    private double weight = 0.35;

    /** 描述非空加分 */
    private double descriptionWeight = 0.35;

    /** 评分 > 0 加分 */
    private double ratingWeight = 0.25;

    /** 图片 URL 非空加分 */
    private double imageWeight = 0.10;

    /** 标签非空加分 */
    private double tagsWeight = 0.10;

    /** 描述为空扣分 */
    private double emptyDescriptionPenalty = 0.20;

    /** 评分缺失或 ≤ 0 扣分 */
    private double zeroRatingPenalty = 0.10;
}
