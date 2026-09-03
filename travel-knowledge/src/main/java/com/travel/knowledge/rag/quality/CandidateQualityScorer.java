package com.travel.knowledge.rag.quality;

import com.travel.knowledge.rag.model.SearchResult;

/**
 * M8-9d：候选质量评分 SPI（Port-Adapter，与 Reranker/WebSearchPort 风格一致）。
 *
 * <p>输入 enrich 前的 {@link SearchResult}，输出 [0,1] 质量分（越高越好）。
 * 默认实现 {@link DeterministicCandidateQualityScorer}（确定性规则，零 LLM）；
 * 未来可替换为 LLM/统计实现（{@code @ConditionalOnMissingBean}），调用方零改动。</p>
 */
public interface CandidateQualityScorer {

    /** 质量分：null 字段必须容错，返回 [0,1] */
    double score(SearchResult candidate);
}
