package com.travel.knowledge.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询理解配置（F44/P3：清单与开关配置化）。
 *
 * <p>对应 yml：{@code travel.rag.query-understanding.*}。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.rag.query-understanding")
public class QueryUnderstandingProperties {

    /** LLM 结构化抽取开关；false 时仅用启发式（零 LLM 成本） */
    private boolean enabled = true;

    /** 意图缓存容量（LRU），0 表示不缓存 */
    private int cacheSize = 256;

    /** 城市清单（启发式兜底与过滤使用） */
    private List<String> cities = new ArrayList<>(List.of(
            "北京", "上海", "广州", "深圳", "杭州", "成都", "西安", "厦门", "南京",
            "天津", "重庆", "武汉", "长沙", "苏州", "桂林", "青岛", "大连", "三亚"));

    /** 类型 → 触发关键词（启发式兜底） */
    private Map<String, List<String>> typeKeywords = new LinkedHashMap<>();

    {
        typeKeywords.put("CULTURE", List.of("文化", "博物馆", "古迹", "历史"));
        typeKeywords.put("NATURE", List.of("自然", "山水", "公园", "湖泊"));
        typeKeywords.put("FOOD", List.of("美食", "小吃", "餐厅"));
        typeKeywords.put("SHOPPING", List.of("购物", "逛街", "商场"));
        typeKeywords.put("FAMILY", List.of("亲子", "家庭", "乐园", "儿童"));
        typeKeywords.put("LEISURE", List.of("休闲", "度假", "放松"));
    }
}
