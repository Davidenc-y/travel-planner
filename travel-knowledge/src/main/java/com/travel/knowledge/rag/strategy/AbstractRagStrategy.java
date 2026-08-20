package com.travel.knowledge.rag.strategy;

import com.travel.knowledge.rag.model.QueryIntent;
import com.travel.knowledge.rag.model.SearchResult;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;

/**
 * M3-4：RAG 策略模板方法（开始日志 → doRetrieve → 完成日志 → 异常降级空列表）。
 * 子类只需实现 {@link #doRetrieve} 与 {@link #getType}。
 */
@Slf4j
public abstract class AbstractRagStrategy implements RagStrategy {

    @Override
    public final List<SearchResult> retrieve(QueryIntent intent, int topK) {
        log.info("[{}] query={}, intent={}, topK={}", getType(), intent.rawQuery(), intent, topK);
        long start = System.currentTimeMillis();
        try {
            List<SearchResult> results = doRetrieve(intent, topK);
            log.info("[{}] 检索完成, 耗时={}ms, 结果数={}",
                    getType(), System.currentTimeMillis() - start, results.size());
            return results;
        } catch (Exception e) {
            log.error("[{}] 检索失败", getType(), e);
            return Collections.emptyList();
        }
    }

    /** 子类实现实际检索逻辑 */
    protected abstract List<SearchResult> doRetrieve(QueryIntent intent, int topK) throws Exception;
}
