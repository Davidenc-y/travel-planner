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
            // M7-8：意图带 type 过滤且检索为空时，放宽为“仅保留城市”重试一次。
            // 背景：杭州/重庆等城市美食类景点数据缺失时，FOOD 过滤会 0 命中；
            // 放宽后仍能返回同城候选，避免规划链路丢失知识库上下文。
            if (results.isEmpty() && intent.type() != null && !intent.type().isBlank()) {
                QueryIntent relaxed = new QueryIntent(
                        intent.city(), null, intent.keywords(), intent.freeOnly(), intent.rawQuery());
                List<SearchResult> fallback = doRetrieve(relaxed, topK);
                if (!fallback.isEmpty()) {
                    log.warn("[{}] 原意图(type={})检索为空，放宽为仅按城市检索后命中 {} 条, city={}",
                            getType(), intent.type(), fallback.size(), intent.city());
                    return fallback;
                }
            }
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
