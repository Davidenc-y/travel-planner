package com.travel.knowledge.rag.support;

import com.travel.knowledge.rag.model.QueryIntent;
import com.travel.knowledge.rag.retrieval.RagRetrievalProperties;
import lombok.RequiredArgsConstructor;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 查询意图 → ES/Milvus 过滤条件构建器（F40/P1）。
 *
 * <p>统一把 {@link QueryIntent} 的字段转为 ES bool 查询与 Milvus search expr，
 * 消除 naive/hybrid 重复过滤代码。</p>
 *
 * <p>F44/P3：free_entry 已写入 ES mapping 与 Milvus schema，freeOnly 参与过滤。</p>
 *
 * <p>M8-9e：city 从“只过滤”升级为“过滤 + 参与评分”——保留 filter(city) 召回约束，
 * 同时追加 should(term(city).boost(cityScoreBoost)) 底分，使名称/描述不含查询词
 * 但城市命中的文档（如故宫博物院）可被 BM25 召回。</p>
 */
@Component
@RequiredArgsConstructor
public class RagFilterBuilder {

    private final RagRetrievalProperties retrievalProperties;

    /** MR-E1：来源权威度注册表（travel.rag.source-registry；空注册表=查询结构零变更，E-33） */
    private SourceAuthorityRegistry sourceAuthorityRegistry;

    /** MR-E1：optional 注入（未注入/空注册表=现状查询结构零变更） */
    @Autowired(required = false)
    void setSourceAuthorityRegistry(SourceAuthorityRegistry sourceAuthorityRegistry) {
        this.sourceAuthorityRegistry = sourceAuthorityRegistry;
    }

    /**
     * 构建 ES 查询：multiMatch(name,description) + city 底分 + 可选 city/type/free 过滤
     */
    public QueryBuilder esQuery(QueryIntent intent, String queryText) {
        var bool = QueryBuilders.boolQuery();
        String text = queryText == null ? "" : queryText.trim();
        List<QueryBuilder> should = new ArrayList<>();
        if (intent != null) {
            // F84：免费语义词仅用于过滤（free_entry=1），不参与文本 must。
            // 否则查询"免费"只存在于 tags，name/description 均无该词 → naive/hybrid BM25 必 0 命中。
            if (intent.freeOnly()) {
                text = stripFreeWords(text);
            }
            if (StringUtils.hasText(intent.city())) {
                bool.filter(QueryBuilders.termQuery("city", intent.city()));
                // M8-9e：city 底分——只影响“能否被召回”与恒定加分，不影响文本相对顺序
                should.add(QueryBuilders.termQuery("city", intent.city())
                        .boost((float) retrievalProperties.getCityScoreBoost()));
            }
            if (StringUtils.hasText(intent.type())) {
                bool.filter(QueryBuilders.termQuery("type", intent.type()));
            }
            if (intent.freeOnly()) {
                bool.filter(QueryBuilders.termQuery("free_entry", 1));
            }
        }
        if (StringUtils.hasText(text)) {
            should.add(QueryBuilders.multiMatchQuery(text, "name", "description"));
        }
        if (!should.isEmpty()) {
            for (QueryBuilder s : should) {
                bool.should(s);
            }
            bool.minimumShouldMatch(1);
        } else {
            bool.must(QueryBuilders.matchAllQuery());
        }
        // MR-E1：来源权威度加权——注册表非空时按 source 追加 should 加权分量（authority=term
        // boost，影响排序）；空注册表=查询结构零变更（E-33）。注意：现 ES 文档尚无 source 字段，
        // 分量实际命中需重灌索引（见《冲突治理字段缺口报告》20260918）。
        if (sourceAuthorityRegistry != null && sourceAuthorityRegistry.isActive()) {
            sourceAuthorityRegistry.getRegistry().forEach((source, authority) ->
                    bool.should(QueryBuilders.termQuery("source", source)
                            .boost((float) (double) authority)));
        }
        return bool;
    }

    private static String stripFreeWords(String text) {
        String t = text;
        for (String w : List.of("免费", "免票", "不花钱", "无门票")) {
            t = t.replace(w, "").trim();
        }
        return t;
    }

    /**
     * 构建 Milvus search expr；无过滤条件返回 null（不传 withExpr）
     */
    public String milvusExpr(QueryIntent intent) {
        if (intent == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        if (StringUtils.hasText(intent.city())) {
            parts.add("city == \"" + intent.city() + "\"");
        }
        if (StringUtils.hasText(intent.type())) {
            parts.add("type == \"" + intent.type() + "\"");
        }
        if (intent.freeOnly()) {
            parts.add("free_entry == 1");
        }
        return parts.isEmpty() ? null : String.join(" && ", parts);
    }
}
