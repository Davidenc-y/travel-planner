package com.travel.knowledge.rag.model;

import com.travel.knowledge.rag.service.QueryUnderstandingService;

import java.util.List;

/**
 * 结构化查询意图（F40/P1）。
 *
 * <p>由 {@link QueryUnderstandingService} 从原始查询中抽取；缺失字段为 null/空。
 * 四个策略统一消费本对象：rawQuery 用于 ES multiMatch 与 Embedding，
 * city/type 等字段用于 ES filter 与 Milvus expr 过滤。</p>
 *
 * @param city     城市（可空）
 * @param type     景点类型 CULTURE/NATURE/FOOD/SHOPPING/FAMILY/LEISURE（可空）
 * @param keywords 有价值检索词（剔除噪声后）
 * @param freeOnly 是否要求免费（P1 仅记录，过滤待 free_entry 入索引）
 * @param rawQuery 原始查询文本（Corrective 重写后为最新查询文本）
 */
public record QueryIntent(
        String city,
        String type,
        List<String> keywords,
        boolean freeOnly,
        String rawQuery
) {

    public QueryIntent {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
    }

    /**
     * 生成仅替换查询文本的新意图（CorrectiveRAG 重写后保留过滤字段）
     */
    public QueryIntent withRawQuery(String newQuery) {
        return new QueryIntent(city, type, keywords, freeOnly, newQuery);
    }
}
