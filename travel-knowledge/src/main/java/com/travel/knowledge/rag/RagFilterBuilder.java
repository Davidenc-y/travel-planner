package com.travel.knowledge.rag;

import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 查询意图 → ES/Milvus 过滤条件构建器（F40/P1）。
 *
 * <p>统一把 {@link QueryIntent} 的字段转为 ES bool filter（city/type）与
 * Milvus search expr（city/type），消除 naive/hybrid 重复过滤代码。</p>
 *
 * <p>F44/P3：free_entry 已写入 ES mapping 与 Milvus schema，freeOnly 参与过滤。</p>
 */
public final class RagFilterBuilder {

    private RagFilterBuilder() {
    }

    /**
     * 构建 ES 查询：multiMatch(name,description) + 可选 city/type 过滤
     */
    public static QueryBuilder esQuery(QueryIntent intent, String queryText) {
        var bool = QueryBuilders.boolQuery();
        bool.must(QueryBuilders.multiMatchQuery(queryText, "name", "description"));
        if (intent != null) {
            if (StringUtils.hasText(intent.city())) {
                bool.filter(QueryBuilders.termQuery("city", intent.city()));
            }
            if (StringUtils.hasText(intent.type())) {
                bool.filter(QueryBuilders.termQuery("type", intent.type()));
            }
            if (intent.freeOnly()) {
                bool.filter(QueryBuilders.termQuery("free_entry", 1));
            }
        }
        return bool;
    }

    /**
     * 构建 Milvus search expr；无过滤条件返回 null（不传 withExpr）
     */
    public static String milvusExpr(QueryIntent intent) {
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
