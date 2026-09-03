package com.travel.knowledge.rag.strategy;

import com.travel.knowledge.rag.model.QueryIntent;
import com.travel.knowledge.rag.model.SearchResult;
import com.travel.knowledge.rag.quality.CandidateQualityFilter;
import com.travel.knowledge.rag.quality.QualityProperties;
import com.travel.knowledge.rag.retrieval.QueryExpander;
import com.travel.knowledge.rag.support.AttractionEnricher;
import com.travel.knowledge.rag.support.RagRoutingMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * M3-4：RAG 策略模板方法（开始日志 → doRetrieve → 完成日志 → 异常降级空列表）。
 * 子类只需实现 {@link #doRetrieve} 与 {@link #getType}。
 *
 * <p>M8-1：模板第四次扩展——统一出口挂载 {@link AttractionEnricher} 结构化补全
 * （前三：开始日志/异常降级/零结果放宽）。补全在检索出口对 score/排序零改动，
 * 四策略中 Naive/Hybrid 直接继承本模板；Self/Corrective 委托 hybridRag 的
 * {@link #retrieve} 亦经由此出口，同样受益。enricher 未注入（如单测直构）时直通。</p>
 *
 * <p>M8-9d：模板第五次扩展——召回池放大 + 出口统一截断。{@link #retrievalPoolSize}
 * 决定 doRetrieve 的召回量；出口先做质量感知截断（{@link CandidateQualityFilter}，
 * 关闭时顺序截断，行为与现状一致）再做结构化补全（补全只对最终 topK 生效，
 * 被淘汰的低质量候选不再触发 web-search 补全）。</p>
 *
 * <p>M8-9e：模板第六次扩展——检索文本扩展。可选注入 {@link QueryExpander}，
 * 在 doRetrieve 前把 intent 替换为扩展后的检索意图（仅 rawQuery 变化），
 * 同时作用于 ES multiMatch 与 KNN embedding；rawQuery 展示/路由/grounding 不变。</p>
 */
@Slf4j
public abstract class AbstractRagStrategy implements RagStrategy {

    /** M8-1：结构化补全器（setter 注入；直构子类的单测场景为 null，模板内直通） */
    protected AttractionEnricher attractionEnricher;

    @Autowired(required = false)
    void setAttractionEnricher(AttractionEnricher attractionEnricher) {
        this.attractionEnricher = attractionEnricher;
    }

    /** M8-2：降级指标（setter 注入；直构单测为 null 时跳过记录） */
    protected RagRoutingMetrics routingMetrics;

    @Autowired(required = false)
    void setRoutingMetrics(RagRoutingMetrics routingMetrics) {
        this.routingMetrics = routingMetrics;
    }

    /** M8-9d：候选质量过滤器（enabled=true 时装配；未注入直通顺序截断） */
    protected CandidateQualityFilter qualityFilter;

    @Autowired(required = false)
    void setQualityFilter(CandidateQualityFilter qualityFilter) {
        this.qualityFilter = qualityFilter;
    }

    /** M8-9d：质量配置（放大池/加权系数） */
    protected QualityProperties qualityProperties;

    @Autowired(required = false)
    void setQualityProperties(QualityProperties qualityProperties) {
        this.qualityProperties = qualityProperties;
    }

    /** M8-9e：检索文本扩展器（未注入直通原意图） */
    protected QueryExpander queryExpander;

    @Autowired(required = false)
    void setQueryExpander(QueryExpander queryExpander) {
        this.queryExpander = queryExpander;
    }

    @Override
    public final List<SearchResult> retrieve(QueryIntent intent, int topK) {
        log.info("[{}] query={}, intent={}, topK={}", getType(), intent.rawQuery(), intent, topK);
        long start = System.currentTimeMillis();
        List<SearchResult> results;
        QueryIntent retrievalIntent = expandRetrievalIntent(intent);
        int poolSize = retrievalPoolSize(topK);
        try {
            results = doRetrieve(retrievalIntent, poolSize);
            // M7-8：意图带 type 过滤且检索为空时，放宽为“仅保留城市”重试一次。
            // 背景：杭州/重庆等城市美食类景点数据缺失时，FOOD 过滤会 0 命中；
            // 放宽后仍能返回同城候选，避免规划链路丢失知识库上下文。
            if (results.isEmpty() && retrievalIntent.type() != null && !retrievalIntent.type().isBlank()) {
                QueryIntent relaxed = new QueryIntent(
                        retrievalIntent.city(), null, retrievalIntent.keywords(),
                        retrievalIntent.freeOnly(), retrievalIntent.rawQuery());
                List<SearchResult> fallback = doRetrieve(relaxed, poolSize);
                if (!fallback.isEmpty()) {
                    log.warn("[{}] 原意图(type={})检索为空，放宽为仅按城市检索后命中 {} 条, city={}",
                            getType(), retrievalIntent.type(), fallback.size(), retrievalIntent.city());
                    results = fallback;
                } else {
                    // M8-2：放宽后仍为空 → 检索降级事件可观测
                    if (routingMetrics != null) {
                        routingMetrics.recordDegraded("empty_relax");
                    }
                    log.warn("[{}] 原意图(type={})检索为空，放宽后仍为空, city={}",
                            getType(), retrievalIntent.type(), retrievalIntent.city());
                }
            }
        } catch (Exception e) {
            log.error("[{}] 检索失败", getType(), e);
            results = Collections.emptyList();
        }
        // M8-9d：统一出口先质量截断（关闭=顺序截断），再结构化补全
        List<SearchResult> selected;
        try {
            selected = qualityFilter == null
                    ? truncate(results, topK)
                    : qualityFilter.select(results, topK);
        } catch (Exception e) {
            log.warn("[{}] 质量截断异常，回退顺序截断: {}", getType(), e.getMessage());
            selected = truncate(results, topK);
        }
        List<SearchResult> enriched = attractionEnricher == null
                ? selected : attractionEnricher.enrich(selected);
        log.info("[{}] 检索完成, 耗时={}ms, 结果数={}",
                getType(), System.currentTimeMillis() - start, enriched.size());
        return enriched;
    }

    /** M8-9e：检索文本扩展（仅替换 rawQuery；未注入或扩展无变化时返回原意图） */
    private QueryIntent expandRetrievalIntent(QueryIntent intent) {
        if (queryExpander == null) {
            return intent;
        }
        String expanded = queryExpander.expand(intent);
        return expanded == null || expanded.equals(intent.rawQuery())
                ? intent : intent.withRawQuery(expanded);
    }

    /**
     * M8-9d：召回池大小钩子。质量开关开启时放大到 candidatePool；
     * 子类（HybridRagStrategy）可覆写叠加精排放大池。默认返回 topK（行为不变）。
     */
    protected int retrievalPoolSize(int topK) {
        return qualityFilter != null && qualityProperties != null
                ? Math.max(topK, qualityProperties.getCandidatePool())
                : topK;
    }

    /** 顺序截断（质量过滤器未装配/异常时的回退；不修改原列表） */
    private static List<SearchResult> truncate(List<SearchResult> results, int topK) {
        if (results == null || results.isEmpty() || topK <= 0) {
            return results == null ? Collections.emptyList() : results;
        }
        return results.size() <= topK ? results : new ArrayList<>(results.subList(0, topK));
    }

    /** 子类实现实际检索逻辑 */
    protected abstract List<SearchResult> doRetrieve(QueryIntent intent, int topK) throws Exception;
}
