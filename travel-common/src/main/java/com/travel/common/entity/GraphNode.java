package com.travel.common.entity;

import lombok.Data;
import java.time.LocalDateTime;

/** AG-1a：GraphRAG 节点（表 graph_node；试点=北京 88 POI；E-45 三件套之三）*/
@Data
public class GraphNode {
    private Long id;            // =t_attraction 主键（docId 口径）
    private String name;
    private String city;
    private String nodeType;    // ATTRACTION/FOOD/AREA
    private String attrs;       // JSON 字符串（抽取事实卡）
    private Integer dirty;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
