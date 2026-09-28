package com.travel.common.entity;

import lombok.Data;
import java.time.LocalDateTime;

/** AG-1a：GraphRAG 边（表 graph_edge；试点=北京 88 POI；E-45 三件套之三）*/
@Data
public class GraphEdge {
    private Long id;            // 自增主键
    private Long srcId;         // 源节点 id（graph_node.id）
    private Long dstId;         // 目标节点 id（graph_node.id）
    private String edgeType;    // NEAR/HAS_FOOD/SAME_TYPE/REL
    private Double weight;      // NEAR=距离衰减权重；语义边=LLM 置信度
    private String attrs;       // JSON 字符串（边属性卡）
    private LocalDateTime createdAt;
}
