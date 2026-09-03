package com.travel.knowledge.rag.retrieval;

import com.travel.knowledge.rag.model.QueryIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * M8-9e：PLANNING 类查询规则化扩展（默认实现）。
 *
 * <p>触发条件（同时满足）：开关开启、city 非空、type 为空、rawQuery 命中任一
 * 规划触发词。扩展词去重追加（已包含的词不重复），避免 Corrective 重写文本二次扩展。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnMissingBean(QueryExpander.class)
public class RuleBasedPlanningQueryExpander implements QueryExpander {

    private final RagRetrievalProperties properties;

    @Override
    public String expand(QueryIntent intent) {
        if (!properties.isPlanningExpansionEnabled() || intent == null) {
            return intent == null ? "" : intent.rawQuery();
        }
        String raw = intent.rawQuery();
        if (!StringUtils.hasText(raw)
                || !StringUtils.hasText(intent.city())
                || StringUtils.hasText(intent.type())) {
            return raw;
        }
        boolean triggered = properties.getPlanningTriggerKeywords().stream()
                .anyMatch(k -> StringUtils.hasText(k) && raw.contains(k));
        if (!triggered) {
            return raw;
        }
        List<String> missing = new ArrayList<>();
        for (String word : properties.getPlanningExpansionWords()) {
            if (StringUtils.hasText(word) && !raw.contains(word)) {
                missing.add(word);
            }
        }
        if (missing.isEmpty()) {
            return raw;
        }
        String expanded = raw + " " + String.join(" ", missing);
        log.debug("[QueryExpand] 检索文本扩展: {} → {}", raw, expanded);
        return expanded;
    }
}
