package com.travel.knowledge.rag.graph;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AG-1c：GraphRAG 邻域扩展配置（对应 yml {@code travel.rag.graph-expand.*}；E-33 三新键默认值全在码=关闭态字节等价）
 *
 * <p>near-decay 不设独立键：衰减因子由 NEAR 边 weight 承载（生成口径=同城 haversine≤2.5km
 * 距离衰减，af6_graph.sql），score=原候选分×boost-weight×边 weight。
 * AI-2a 四新键（expand-hops/expand-total-paths/expand-fanout/expand-timeout-ms）默认值全在码=零 yml
 * （E-56 两跳五防护配套）；hopDecay=0.5 为常量不设键。</p>
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

    /** AI-2a：扩展跳深（默认 1=E-33 单跳字节等价；2=两跳受限 BFS；其他值启动即拒 IllegalStateException=E-56 防护五） */
    private int expandHops = 1;

    /** AI-2a：两跳扩展路径总数上限（E-56 防护二：paths 计数顶停防组合爆炸） */
    private int expandTotalPaths = 64;

    /** AI-2a：每跳扇出上限（E-56 防护三：逐跳截断防大邻域淹没） */
    private int expandFanout = 8;

    /** AI-2a：两跳扩展硬超时 ms（E-56 防护四：超时 fail-open 截断+WARN，防热路径劣化） */
    private int expandTimeoutMs = 50;

    /** AI-2b：语义边路由白名单（默认 [NEAR]=既有同城口径零变化；消费 NeighborInfo.edgeType 免旁路二次查询） */
    private List<String> expandEdgeTypes = List.of("NEAR");
}
