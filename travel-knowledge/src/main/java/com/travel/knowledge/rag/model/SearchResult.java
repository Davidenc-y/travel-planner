package com.travel.knowledge.rag.model;

import lombok.Builder;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * RAG 检索结果 DTO
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResult {

    /** 文档 ID */
    private String docId;

    /** 标题（景点名称） */
    private String title;

    /** 摘要（景点描述） */
    private String snippet;

    /** 融合后得分 */
    private double score;

    /** 关键词标签 */
    private List<String> keywords;

    /** 来源日期 */
    private String sourceDate;

    /** F121/P1：景点图片 URL（ES/Milvus 检索结果带图；无图为空） */
    private String imageUrl;

    /** 检索来源：milvus / es / hybrid / self_rag / corrective_rag */
    private String source;

    // ===== M8-1：结构化事实字段（由 AttractionEnricher 从 MySQL t_attraction 补全）=====
    // null 语义约定（全链统一，M8-2/3/4 依赖）：字段为 null 表示知识库无此数据
    //（触发 M8-4 联网兜底判定）；禁止使用空字符串（enricher 统一转换为 null）。

    /** 城市 */
    private String city;

    /** 类型：CULTURE/NATURE/FOOD/SHOPPING/FAMILY/LEISURE */
    private String type;

    /** 详细地址 */
    private String address;

    /** 开放时间（如 "09:00-17:00"；库内为空时为 null） */
    private String openHours;

    /** 门票价格（元；null=未知，免费由 freeEntry 表达） */
    private Double ticketPrice;

    /** 是否免费 */
    private Boolean freeEntry;

    /** 评分 0-5 */
    private Double rating;

    /** 推荐游玩时长（如 "3-4小时"） */
    private String recommendedDuration;

    /** 数据源（amap/manual/enrich；M8-4 后可含 web_enrich） */
    private String dataSource;
}
