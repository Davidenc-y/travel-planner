package com.travel.knowledge.rag.retrieval;

import com.travel.knowledge.rag.model.QueryIntent;

/**
 * M8-9e：检索文本扩展 SPI（Port-Adapter，与 Reranker/QualityScorer 风格一致）。
 *
 * <p>只影响检索文本（ES multiMatch + KNN embedding），不修改 rawQuery 展示、
 * 路由与 grounding。默认实现 {@link RuleBasedPlanningQueryExpander}（确定性规则，
 * 零 LLM）；未来可替换为 LLM/统计扩展（{@code @ConditionalOnMissingBean}）。</p>
 */
public interface QueryExpander {

    /**
     * @param intent 查询意图（非 null）
     * @return 用于检索的文本；不扩展时原样返回（可为 null/空，保持安全）
     */
    String expand(QueryIntent intent);
}
