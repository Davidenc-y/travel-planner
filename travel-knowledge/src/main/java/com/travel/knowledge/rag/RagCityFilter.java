package com.travel.knowledge.rag;

import java.util.Set;

/**
 * RAG 查询城市识别（F39）
 *
 * <p>当查询包含城市名时，Hybrid/Naive 检索按城市限定，避免"北京文化景点"
 * 混入深圳等异地景点的命中（TC-20 实测问题）。城市清单与数据集
 * （scripts/data/attractions_raw.json / init_mysql.sql）对齐；后续可配置化
 * 或改为从 DB distinct 获取。</p>
 */
public final class RagCityFilter {

    private static final Set<String> KNOWN_CITIES = Set.of(
            "北京", "上海", "广州", "深圳", "杭州", "成都", "西安", "厦门", "南京",
            "天津", "重庆", "武汉", "长沙", "苏州", "桂林", "青岛", "大连", "三亚"
    );

    private RagCityFilter() {
    }

    /**
     * 从查询中识别城市名；未命中返回 null（不做城市限定）
     */
    public static String detect(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String city : KNOWN_CITIES) {
            if (query.contains(city)) {
                return city;
            }
        }
        return null;
    }
}
