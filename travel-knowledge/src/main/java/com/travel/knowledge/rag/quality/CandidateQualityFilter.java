package com.travel.knowledge.rag.quality;

import com.travel.knowledge.rag.model.SearchResult;
import com.travel.knowledge.rag.support.RagRoutingMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * M8-9d：候选质量感知截断（enabled=true 时装配）。
 *
 * <p>对放大后的召回池按「融合分 × (1 + α×质量分)」降序截断 topK：
 * 只重排/截断、不删除，全部低质量时仍返回 topK（召回不为空）；
 * 精确查询相关分显著高时质量加权的相对影响小，不会误伤唯一相关冷门 POI。
 * 任何异常 fail-open 回退顺序截断（复用 {@code recordDegraded("quality_fallback")}）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "travel.rag.quality", name = "enabled", havingValue = "true")
public class CandidateQualityFilter {

    private final CandidateQualityScorer scorer;
    private final QualityProperties properties;
    private final RagRoutingMetrics routingMetrics;

    /**
     * @param pool  放大后的候选池（融合分降序）
     * @param topK  最终返回条数
     * @return 质量加权截断后的 topK（≤ topK）
     */
    public List<SearchResult> select(List<SearchResult> pool, int topK) {
        if (pool == null || pool.isEmpty() || topK <= 0 || pool.size() <= topK) {
            return pool;
        }
        try {
            List<SearchResult> selected = pool.stream()
                    .sorted(Comparator.comparingDouble(this::adjustedScore).reversed()
                            .thenComparingInt(pool::indexOf))
                    .limit(topK)
                    .toList();
            log.info("[QualityFilter] 候选池 {} 条 → topK {} 条（质量加权截断）",
                    pool.size(), selected.size());
            return selected;
        } catch (Exception e) {
            log.warn("[QualityFilter] 质量截断失败，回退顺序截断: {}", e.getMessage());
            if (routingMetrics != null) {
                routingMetrics.recordDegraded("quality_fallback");
            }
            return new ArrayList<>(pool.subList(0, Math.min(topK, pool.size())));
        }
    }

    /** 最终分：融合分 × (1 + α×质量分)；融合分 ≤ 0 时纯按质量分排序 */
    private double adjustedScore(SearchResult r) {
        double quality = scorer.score(r);
        double score = r.getScore();
        return score > 0 ? score * (1 + properties.getWeight() * quality) : quality;
    }
}
