package com.travel.knowledge.rag.rerank;

import com.travel.knowledge.rag.model.SearchResult;

import java.util.List;

/**
 * M4-6：Rerank SPI（knowledge 景点路）。
 *
 * <p>对混合检索（BM25+KNN+RRF）融合后的候选做精排，<b>对上游透明</b>：上游
 * Self/Corrective 装饰器与调用方零改动即获益。实现要点：</p>
 * <ul>
 *   <li><b>只重排、不截断</b>（M8-9d）：最终 topK 截断统一由
 *       {@code AbstractRagStrategy} 出口负责（质量截断或顺序截断），
 *       消除 Noop/DashScope 两处重复截断；</li>
 *   <li><b>fail-open</b>：任何失败按原顺序返回完整候选池（只降级不阻断）；</li>
 *   <li>默认实现 {@link NoopReranker}（type=none，直通），行为与现状一致；</li>
 *   <li>{@link #passthrough()} 标记直通实现，供策略侧决定是否放大内部候选池
 *       （直通时由质量配置决定是否放大；非直通时放大到 candidate-pool）。</li>
 * </ul>
 */
public interface Reranker {

    /**
     * 精排候选列表（只重排，不截断；截断由模板出口统一负责）。
     *
     * @param query      原始查询文本（非意图改写值）
     * @param candidates 融合后的候选池（按融合分降序）
     * @return 精排后列表（数量不变）；实现失败时 fail-open 返回原顺序
     */
    List<SearchResult> rerank(String query, List<SearchResult> candidates);

    /**
     * 是否为直通实现（不改变候选顺序）。
     * 策略侧据此决定内部检索量：直通时保持原 topK（与现状逐字节一致），
     * 真实精排时放大到 candidate-pool 再截回（透明放大）。
     */
    default boolean passthrough() {
        return false;
    }
}
