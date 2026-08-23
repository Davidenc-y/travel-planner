package com.travel.knowledge.rag.rerank;

import com.travel.knowledge.rag.model.SearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * M4-6：直通 Reranker（默认实现，type=none 或缺省生效）。
 *
 * <p>按原顺序截断 topK，不改序、零外部调用；配合
 * {@link Reranker#passthrough()}=true 时策略侧不放大内部检索量，
 * <b>noop 下结果与原逻辑完全一致</b>（回归零风险）。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "travel.rag.rerank.type", havingValue = "none", matchIfMissing = true)
public class NoopReranker implements Reranker {

    @Override
    public List<SearchResult> rerank(String query, List<SearchResult> candidates, int topK) {
        if (candidates == null || topK <= 0) {
            return List.of();
        }
        return candidates.stream().limit(topK).toList();
    }

    @Override
    public boolean passthrough() {
        return true;
    }
}
