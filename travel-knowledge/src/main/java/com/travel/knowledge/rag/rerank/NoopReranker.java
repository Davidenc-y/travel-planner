package com.travel.knowledge.rag.rerank;

import com.travel.knowledge.rag.model.SearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * M4-6：直通 Reranker（默认实现，type=none 或缺省生效）。
 *
 * <p>M8-9d：只保持原顺序、不截断（最终 topK 由模板出口统一截断）；
 * 零外部调用；配合 {@link Reranker#passthrough()}=true，
 * noop + 质量开关关闭时输出与原逻辑完全一致（回归零风险）。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "travel.rag.rerank.type", havingValue = "none", matchIfMissing = true)
public class NoopReranker implements Reranker {

    @Override
    public List<SearchResult> rerank(String query, List<SearchResult> candidates) {
        if (candidates == null) {
            return List.of();
        }
        return candidates;
    }

    @Override
    public boolean passthrough() {
        return true;
    }
}
