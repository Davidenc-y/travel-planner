package com.travel.knowledge.rag.rerank;

import com.travel.knowledge.rag.model.SearchResult;

import java.util.List;

/**
 * M4-6：Rerank SPI（knowledge 景点路）。
 *
 * <p>对混合检索（BM25+KNN+RRF）融合后的候选做精排，<b>对上游透明</b>：上游
 * Self/Corrective 装饰器与调用方零改动即获益。实现要点：</p>
 * <ul>
 *   <li><b>fail-open</b>：任何失败按原顺序截断 topK 返回（只降级不阻断）；</li>
 *   <li>默认实现 {@link NoopReranker}（type=none，直通），行为与现状一致；</li>
 *   <li>{@link #passthrough()} 标记直通实现，供策略侧决定是否放大内部候选池
 *       （直通时不放大，保证 noop 下结果与原逻辑<b>完全</b>一致，回归零风险）。</li>
 * </ul>
 */
public interface Reranker {

    /**
     * 精排候选列表。
     *
     * @param query      原始查询文本（非意图改写值）
     * @param candidates 融合后的候选（按融合分降序）
     * @param topK       最终返回条数
     * @return 精排后列表（≤ topK）；实现失败时 fail-open 返回原顺序截断 topK
     */
    List<SearchResult> rerank(String query, List<SearchResult> candidates, int topK);

    /**
     * 是否为直通实现（不改变候选顺序）。
     * 策略侧据此决定内部检索量：直通时保持原 topK（与现状逐字节一致），
     * 真实精排时放大到 candidate-pool 再截回（透明放大）。
     */
    default boolean passthrough() {
        return false;
    }
}
