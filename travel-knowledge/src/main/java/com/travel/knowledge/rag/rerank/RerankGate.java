package com.travel.knowledge.rag.rerank;

import com.travel.knowledge.rag.model.SearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * MR-B1：Rerank 低置信阈值门控（幻觉规避第二道闸，文章 §10.2）。
 *
 * <p>输入 rerank 后分数列表 → 输出 {@link GateResult}（gated/topScore）。
 * 门控键 {@code travel.rag.rerank.gate-threshold}（{@link RerankProperties#getGateThreshold()}）：
 * <b>默认 0.0=关闭</b>（E-33：evaluate/apply 均零路径变更、零日志、返回原列表引用）；
 * (0,1] 生效，topScore &lt; threshold 判低置信，命中时对结果集打 lowConfidence 标记
 * （不改变返回结构，仅附加字段）并输出 {@code [RerankGate]} 单行 INFO。</p>
 *
 * <p>非 Spring bean：由消费方（HybridRagStrategy）基于已注入的 {@link RerankProperties} 构造，
 * 避免既有构造签名与测试锚点变更。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@RequiredArgsConstructor
public class RerankGate {

    private final RerankProperties properties;

    /**
     * 门控判定结果：gated=true 表示整组结果低于阈值（低置信）；topScore 为组内最高分。
     */
    public record GateResult(boolean gated, double topScore) {
    }

    /**
     * 门控判定：阈值 0.0（关闭）恒 not-gated；开启时空分数集按 topScore=0 判定。
     */
    public GateResult evaluate(List<Double> scores) {
        double threshold = properties.getGateThreshold();
        double topScore = (scores == null || scores.isEmpty())
                ? 0.0
                : scores.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        boolean gated = threshold > 0.0 && topScore < threshold;
        if (gated) {
            log.info("[RerankGate] topScore={} threshold={} gated=true", topScore, threshold);
        }
        return new GateResult(gated, topScore);
    }

    /**
     * 消费点接线：关闭态（threshold&lt;=0 或空结果）原样返回（E-33 等价路径）；
     * 开启且 gated 时对结果集逐条打 lowConfidence=true 标记。
     */
    public List<SearchResult> apply(List<SearchResult> results) {
        if (properties.getGateThreshold() <= 0.0 || results == null || results.isEmpty()) {
            return results;
        }
        GateResult result = evaluate(results.stream().map(SearchResult::getScore).toList());
        if (result.gated()) {
            results.forEach(r -> r.setLowConfidence(Boolean.TRUE));
        }
        return results;
    }
}
