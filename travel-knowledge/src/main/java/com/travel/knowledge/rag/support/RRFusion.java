package com.travel.knowledge.rag.support;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * RRF (Reciprocal Rank Fusion) 融合算法
 *
 * <p>用于混合检索中融合 BM25 和 KNN 两路检索结果。</p>
 *
 * <p>公式: score = 1 / (k + rank)，k = 60（行业标准常数）</p>
 *
 * <p>F77/B4-1：由 {@code com.travel.knowledge.memory} 迁移至 rag/support（K5 清理，
 * 消除"memory"命名误导）；仅改包名，逻辑不变。</p>
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
                            item.sourceDate(),
                            item.imageUrl());
                })
                .collect(Collectors.toList());
    }

    /**
     * M4-1b：通用 RRF 融合（Map/任意元素形态）。
     *
     * <p>收敛 {@code SessionContextService.fuse/addRanks} 内联实现（同一算法两份代码）。
     * 行为与原内联版一致：同 id 分数累加、先出现者优先保留（first 列表在前）、
     * 融合分降序、limit topK、<b>分数不四舍五入</b>（区别于 {@link #fuse} 的 4 位小数）。</p>
     *
     * @param firstRanked  第一路结果（按相关性排序，rank 从 1 起）
     * @param secondRanked 第二路结果（按相似度排序，rank 从 1 起）
     * @param topK         返回条数上限
     * @param idOf         元素唯一标识提取器
     * @param <T>          元素类型
     * @return 融合排序后的 (元素, 融合分) 列表
     */
    public static <T> List<RankedItem<T>> fuseGeneric(
            List<T> firstRanked, List<T> secondRanked, int topK, Function<T, String> idOf) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, T> byId = new LinkedHashMap<>();
        addRanks(firstRanked, scores, byId, idOf);
        addRanks(secondRanked, scores, byId, idOf);
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(Math.max(0, topK))
                .map(e -> new RankedItem<>(byId.get(e.getKey()), e.getValue()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static <T> void addRanks(List<T> hits, Map<String, Double> scores,
                                     Map<String, T> byId, Function<T, String> idOf) {
        for (int i = 0; i < hits.size(); i++) {
            T hit = hits.get(i);
            String id = idOf.apply(hit);
            scores.merge(id, 1.0 / (K + i + 1), Double::sum);
            byId.putIfAbsent(id, hit);
        }
    }

    /** M4-1b：泛型融合结果（元素 + 未舍入融合分） */
    public record RankedItem<T>(T item, double score) {}

    /** 单条检索评分项 */
    public record ScoredItem(
            String docId,
            String title,
            String snippet,
            double rawScore,
            List<String> keywords,
            String sourceDate,
            String imageUrl
    ) {}

    /** RRF 融合结果 */
    public record FusionResult(
            String docId,
            String title,
            String snippet,
            double fusedScore,
            List<String> keywords,
            String sourceDate,
            String imageUrl
    ) {}
}
