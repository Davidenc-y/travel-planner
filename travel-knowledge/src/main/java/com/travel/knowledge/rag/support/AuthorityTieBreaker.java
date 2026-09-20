package com.travel.knowledge.rag.support;

import com.travel.knowledge.rag.model.SearchResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * RK-9：权威裁决 tie-break。仅当相邻两结果分数相对差 &lt; epsilon 时按来源权威度换位；
 * travel.rag.source-registry.tie-break-enabled=false（默认）时原样返回（字节等价）。
 * 裁决顺序遵循"适用性(城市过滤已在检索层)→权威度→(时间字段暂无)"。
 */
@Component
public class AuthorityTieBreaker {

    private final SourceAuthorityRegistry registry;
    private final SourceRegistryTieBreakProperties properties;

    public AuthorityTieBreaker(SourceAuthorityRegistry registry, SourceRegistryTieBreakProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    public List<SearchResult> apply(List<SearchResult> ranked) {
        if (!properties.isTieBreakEnabled() || ranked == null || ranked.size() < 2) {
            return ranked;
        }
        List<SearchResult> sorted = new ArrayList<>(ranked);
        // 插入排序变体：i 从 1 起，当 score(i-1)-score(i) 相对差 < epsilon
        // 且 authority(source(i)) > authority(source(i-1)) 时交换，指针回退重试
        for (int i = 1; i < sorted.size(); i++) {
            int j = i;
            while (j > 0 && tieBreaksAhead(sorted.get(j - 1), sorted.get(j))) {
                SearchResult tmp = sorted.get(j - 1);
                sorted.set(j - 1, sorted.get(j));
                sorted.set(j, tmp);
                j--;
            }
        }
        return sorted;
    }

    /** 相邻对裁决：相对差（gap/max）&lt; epsilon（并列）且后位权威度更高 → 换位 */
    private boolean tieBreaksAhead(SearchResult ahead, SearchResult behind) {
        double max = Math.max(Math.abs(ahead.getScore()), Math.abs(behind.getScore()));
        double gap = Math.abs(ahead.getScore() - behind.getScore());
        if (max <= 0 || gap / max >= properties.getTieBreakEpsilon()) {
            return false;
        }
        return authorityOf(behind) > authorityOf(ahead);
    }

    /** authority 取值：registry.scoreOf(source)（骨架注释），缺失源计 0.0——实测 API=getRegistry().getOrDefault */
    private double authorityOf(SearchResult r) {
        String source = r.getSource();
        if (source == null) {
            return 0.0;
        }
        Double v = registry.getRegistry().get(source);
        return v == null ? 0.0 : v;
    }
}
