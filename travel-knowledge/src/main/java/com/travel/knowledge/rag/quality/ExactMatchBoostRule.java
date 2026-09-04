package com.travel.knowledge.rag.quality;

import com.travel.knowledge.rag.model.SearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * M9-1：精确名称命中优先（确定性规则，零 LLM）。
 *
 * <p>规则：查询串（去空白/全角归一化后）与候选 name 满足以下任一 → 该候选置顶组：
 * <ol>
 *   <li>全等（归一化后 equals）；</li>
 *   <li>查询词包含 name 或 name 包含查询词，且长度差 ≤ 2
 *       （如 "雍和宫" vs "雍和宫（北京）" 的窄后缀场景）。</li>
 * </ol>
 * 置顶组内部保持既有融合分排序；非置顶组顺序不变。全不命中时返回原列表，
 * 行为与现状逐字节一致。</p>
 */
@Slf4j
@Component
public class ExactMatchBoostRule {

    /**
     * 对已截断的候选列表应用精确名称命中置顶。
     *
     * @param candidates 出口截断后的候选（topK）
     * @param query      原始查询词（M8-9e 扩展前的原文，不含规则化扩展词）
     * @return 置顶后的新列表；无命中时返回入参原对象
     */
    public List<SearchResult> apply(List<SearchResult> candidates, String query) {
        if (candidates == null || candidates.size() <= 1 || query == null || query.isBlank()) {
            return candidates;
        }
        String q = normalize(query);
        if (q.isEmpty()) {
            return candidates;
        }
        List<SearchResult> matched = new ArrayList<>();
        List<SearchResult> rest = new ArrayList<>();
        for (SearchResult candidate : candidates) {
            if (candidate == null || candidate.getTitle() == null) {
                rest.add(candidate);
                continue;
            }
            String name = normalize(candidate.getTitle());
            if (name.isEmpty()) {
                rest.add(candidate);
                continue;
            }
            boolean exact = q.equals(name);
            boolean nearContain = containsEither(q, name)
                    && Math.abs(q.length() - name.length()) <= 2;
            if (exact || nearContain) {
                matched.add(candidate);
            } else {
                rest.add(candidate);
            }
        }
        if (matched.isEmpty()) {
            return candidates; // 回退：行为等价
        }
        log.info("[ExactMatch] 精确名称命中置顶: query={}, matched={}",
                query, matched.stream().map(SearchResult::getTitle).toList());
        List<SearchResult> boosted = new ArrayList<>(matched.size() + rest.size());
        boosted.addAll(matched);
        boosted.addAll(rest);
        return boosted;
    }

    /** q 与 name 互相包含（双向，去重后的子串关系） */
    private static boolean containsEither(String a, String b) {
        return a.contains(b) || b.contains(a);
    }

    /** 归一化：NFKC（全角→半角、兼容字符折叠）+ 去所有空白 + 小写 */
    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("\\s+", "")
                .toLowerCase();
    }
}
