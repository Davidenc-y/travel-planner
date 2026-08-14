package com.travel.knowledge.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Corrective-RAG 策略（查询重写）
 *
 * <p>借鉴开源 Travel-Agent 的 Corrective-RAG 思想：</p>
 * <ol>
 *   <li>Step 1: 用原始 query 执行 HybridRAG 检索</li>
 *   <li>Step 2: 检查结果质量（数量 + 平均长度）</li>
 *   <li>Step 3: 质量差时，用 LLM 重写 query</li>
 *   <li>Step 4: 用重写后的 query 再次检索</li>
 *   <li>Step 5: 合并去重两次结果</li>
 * </ol>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Component("correctiveRag")
public class CorrectiveRagStrategy implements RagStrategy {

    /** 结果质量阈值：最少结果数 */
    private static final int MIN_RESULTS = 3;

    /**
     * 结果质量阈值：最小平均长度（F39 修正）。
     * 原 50 对景点 description（通常 10~40 字）过高，导致几乎每次查询都触发重写、
     * 且重写后结果与初始几乎相同；15 与短文本描述匹配，仅在真正质量差时重写。
     */
    private static final int MIN_AVG_LENGTH = 15;

    private final HybridRagStrategy hybridStrategy;
    private final ChatModel chatModel;

    @Autowired
    public CorrectiveRagStrategy(HybridRagStrategy hybridStrategy, ChatModel chatModel) {
        this.hybridStrategy = hybridStrategy;
        this.chatModel = chatModel;
    }

    @Override
    public List<SearchResult> retrieve(QueryIntent intent, int topK) {
        log.info("[CorrectiveRAG] query={}, intent={}", intent.rawQuery(), intent);

        // Step 1: 原始 query 检索
        List<SearchResult> initialResults = hybridStrategy.retrieve(intent, topK);
        log.info("[CorrectiveRAG] 初始检索 {} 条", initialResults.size());

        // Step 2: 检查质量
        if (checkQuality(initialResults)) {
            log.info("[CorrectiveRAG] 质量达标，直接返回");
            initialResults.forEach(r -> r.setSource("corrective_rag"));
            return initialResults;
        }

        // Step 3: LLM 重写 query
        String reformulatedQuery = reformulateQuery(intent.rawQuery(), initialResults);
        log.info("[CorrectiveRAG] 重写 query: {} → {}", intent.rawQuery(), reformulatedQuery);
        // F40/P1：重写后保留原意图的 city/type 过滤，仅替换查询文本。
        QueryIntent correctedIntent = intent.withRawQuery(reformulatedQuery);

        // Step 4: 重写后检索
        List<SearchResult> correctedResults = hybridStrategy.retrieve(correctedIntent, topK);
        log.info("[CorrectiveRAG] 重写后检索 {} 条", correctedResults.size());

        // Step 5: 合并去重
        List<SearchResult> merged = mergeResults(initialResults, correctedResults, topK);
        log.info("[CorrectiveRAG] 合并去重后 {} 条", merged.size());
        return merged;
    }

    /**
     * 检查结果质量
     */
    private boolean checkQuality(List<SearchResult> results) {
        if (results.size() < MIN_RESULTS) {
            return false;
        }
        double avgLength = results.stream()
                .mapToInt(r -> r.getSnippet() != null ? r.getSnippet().length() : 0)
                .average()
                .orElse(0);
        return avgLength >= MIN_AVG_LENGTH;
    }

    /**
     * LLM 重写查询
     */
    private String reformulateQuery(String originalQuery, List<SearchResult> previousResults) {
        try {
            String context = previousResults.stream()
                    .limit(2)
                    .map(r -> r.getTitle() + ": " + (r.getSnippet() != null ? r.getSnippet().substring(0, Math.min(100, r.getSnippet().length())) : ""))
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("");

            String prompt = String.format("""
                    基于以下上下文，优化搜索查询以获得更好的旅游规划结果。

                    原始查询：%s
                    相关上下文：%s

                    请提供一个更精确的搜索查询（只返回查询语句，不要其他内容）：
                    """, originalQuery, context);

            String result = chatModel.call(prompt);
            return result != null ? result.trim() : originalQuery + " 旅游攻略 景点推荐";
        } catch (Exception e) {
            log.warn("[CorrectiveRAG] LLM 重写失败，使用兜底查询: {}", e.getMessage());
            return originalQuery + " 旅游攻略 景点推荐";
        }
    }

    /**
     * 合并去重两次检索结果
     */
    private List<SearchResult> mergeResults(List<SearchResult> results1, List<SearchResult> results2, int topK) {
        Set<String> seen = new HashSet<>();
        List<SearchResult> merged = new ArrayList<>();

        for (SearchResult r : results1) {
            String key = r.getDocId() != null ? r.getDocId() : r.getTitle();
            if (key != null && seen.add(key)) {
                merged.add(r);
            }
        }
        for (SearchResult r : results2) {
            String key = r.getDocId() != null ? r.getDocId() : r.getTitle();
            if (key != null && seen.add(key)) {
                merged.add(r);
            }
        }

        List<SearchResult> limited = merged.stream().limit(topK).toList();
        limited.forEach(r -> r.setSource("corrective_rag"));
        return limited;
    }

    @Override
    public String getType() {
        return "corrective_rag";
    }
}
