package com.travel.knowledge.rag.quality;

import com.travel.knowledge.rag.model.SearchResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * M8-9d：确定性候选质量评分（默认实现）。
 *
 * <p>信号全部取自 enrich 前已有的 SearchResult：描述非空 / 评分>0 / 图片非空 /
 * 标签非空；惩罚：描述为空 / 评分缺失或≤0。manual 来源信号暂未启用
 * （ES/Milvus 未存 source 字段，评分>0 已是良好代理），未来 ETL 补字段后在此扩展。</p>
 */
@Component
@RequiredArgsConstructor
public class DeterministicCandidateQualityScorer implements CandidateQualityScorer {

    private final QualityProperties properties;

    @Override
    public double score(SearchResult r) {
        if (r == null) {
            return 0;
        }
        double bonus = 0;
        boolean hasDescription = isNotBlank(r.getSnippet());
        boolean hasRating = r.getRating() != null && r.getRating() > 0;
        if (hasDescription) {
            bonus += properties.getDescriptionWeight();
        }
        if (hasRating) {
            bonus += properties.getRatingWeight();
        }
        if (isNotBlank(r.getImageUrl())) {
            bonus += properties.getImageWeight();
        }
        if (r.getKeywords() != null && !r.getKeywords().isEmpty()) {
            bonus += properties.getTagsWeight();
        }
        double penalty = 0;
        if (!hasDescription) {
            penalty += properties.getEmptyDescriptionPenalty();
        }
        if (!hasRating) {
            penalty += properties.getZeroRatingPenalty();
        }
        return Math.max(0, Math.min(1, bonus - penalty));
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
