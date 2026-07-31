package com.travel.knowledge.memory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RRF (Reciprocal Rank Fusion) 融合算法
 *
 * <p>用于混合检索中融合 BM25 和 KNN 两路检索结果。</p>
 *
 * <p>公式: score = 1 / (k + rank)，k = 60（行业标准常数）</p>
 *
 * <p>直接复用 interview-memory RRFusion，仅改包名。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
public class RRFusion {

    /** RRF 常数 k（控制排名影响力） */
    private static final int K = 60;

    /**
     * RRF 融合两路结果
     *
     * @param bm25Results BM25 文本检索结果（按相关性排序）
     * @param knnResults  KNN 向量检索结果（按相似度排序）
     * @param topK        返回 Top-K 结果
     * @return 融合排序后的结果
     */
    public static List<FusionResult> fuse(
            List<ScoredItem> bm25Results,
            List<ScoredItem> knnResults,
            int topK) {

        Map<String, Double> fusedScores = new LinkedHashMap<>();
        Map<String, ScoredItem> itemMap = new LinkedHashMap<>();

        // 处理 BM25 结果: rank 从 1 开始
        for (int i = 0; i < bm25Results.size(); i++) {
            ScoredItem item = bm25Results.get(i);
            String docId = item.docId();
            fusedScores.merge(docId, 1.0 / (K + i + 1), Double::sum);
            itemMap.putIfAbsent(docId, item);
        }

        // 处理 KNN 结果: rank 从 1 开始
        for (int i = 0; i < knnResults.size(); i++) {
            ScoredItem item = knnResults.get(i);
            String docId = item.docId();
            fusedScores.merge(docId, 1.0 / (K + i + 1), Double::sum);
            itemMap.putIfAbsent(docId, item);
        }

        // 按融合得分降序 + Top-K
        return fusedScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> {
                    ScoredItem item = itemMap.get(entry.getKey());
                    return new FusionResult(
                            item.docId(),
                            item.title(),
                            item.snippet(),
                            Math.round(entry.getValue() * 10000.0) / 10000.0,
                            item.keywords(),
                            item.sourceDate());
                })
                .collect(Collectors.toList());
    }

    /** 单条检索评分项 */
    public record ScoredItem(
            String docId,
            String title,
            String snippet,
            double rawScore,
            List<String> keywords,
            String sourceDate
    ) {}

    /** RRF 融合结果 */
    public record FusionResult(
            String docId,
            String title,
            String snippet,
            double fusedScore,
            List<String> keywords,
            String sourceDate
    ) {}
}
