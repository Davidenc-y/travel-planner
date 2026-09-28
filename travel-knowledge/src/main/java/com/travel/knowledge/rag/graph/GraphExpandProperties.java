package com.travel.knowledge.rag.graph;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AG-1c：GraphRAG 邻域扩展配置（对应 yml {@code travel.rag.graph-expand.*}；E-33 三新键默认值全在码=关闭态字节等价）
 *
 * <p>near-decay 不设独立键：衰减因子由 NEAR 边 weight 承载（生成口径=同城 haversine≤2.5km
 * 距离衰减，af6_graph.sql），score=原候选分×boost-weight×边 weight。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.rag.graph-expand")
public class GraphExpandProperties {

    /** 图扩展总开关（默认 false=关闭态 expand() 首行直通返回原列表，字节等价 E-33；转正判据=决策门双轨） */
    private boolean enabled = false;

    /** 单次扩展邻域项进池上限（P0⑫ 界：&gt;0；超限截断防池爆炸） */
    private int expandTopK = 8;

    /** 邻域项 boost 权重（P0⑫ 界：(0,1]；score=原候选分×boost-weight×边衰减权重） */
    private double boostWeight = 0.8;
}
