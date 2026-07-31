package com.travel.knowledge.rag;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * RAG 检索结果 DTO
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Data
@Builder
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

    /** 检索来源：milvus / es / hybrid / self_rag / corrective_rag */
    private String source;
}
