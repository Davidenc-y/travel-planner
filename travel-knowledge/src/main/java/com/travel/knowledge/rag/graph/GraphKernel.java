package com.travel.knowledge.rag.graph;

import com.travel.common.entity.GraphEdge;
import com.travel.common.entity.GraphNode;
import com.travel.knowledge.repository.GraphEdgeMapper;
import com.travel.knowledge.repository.GraphNodeMapper;
import lombok.extern.slf4j.Slf4j;
import org.jgrapht.graph.SimpleWeightedGraph;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AG-1b：GraphRAG 内存图内核。E-53：启动全量构建不可变快照；刷新=整体替换 volatile 引用
 * （读侧无锁）；热路径 neighbors() 纯内存查询零 IO 零构建。图空/加载失败=空图 fail-open。
 */
@Slf4j
@Component
public class GraphKernel {

    private final GraphNodeMapper nodeMapper;
    private final GraphEdgeMapper edgeMapper;

    /** 不可变快照（volatile 引用整体替换；SimpleWeightedGraph 构建后不再改动） */
    private volatile SimpleWeightedGraph<Long, DefaultWeightedEdge> snapshot = emptyGraph();
    private volatile int nodeCount = 0;

    public GraphKernel(GraphNodeMapper nodeMapper, GraphEdgeMapper edgeMapper) {
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
    }

    private static SimpleWeightedGraph<Long, DefaultWeightedEdge> emptyGraph() {
        return new SimpleWeightedGraph<>(DefaultWeightedEdge.class);
    }

    @PostConstruct
    void loadOnStartup() {
        try {
            rebuild();
            log.info("[GraphKernel] 启动加载完成: nodes={}, edges={}", nodeCount, snapshot.edgeSet().size());
        } catch (Exception e) {
            log.warn("[GraphKernel] 启动加载失败，空图 fail-open: {}", e.getMessage());
        }
    }

    /**
     * AG-1b 日级刷新（travel.rag.graph.refresh-cron 默认每日 3:30 错峰 ETL 3:00）。
     * 修订（2026-09-29 追问R2）：原设计脏门控（dirty>0 才重建）存在真实缺陷——change-scan
     * 生产默认 false（Nacos 实证），脏标记永不产生→图静默陈旧；88 节点级全量重建毫秒级，
     * 改为无条件日级全量重建（脏标记保留为未来规模化优化位，非正确性依赖）。
     */
    @Scheduled(cron = "${travel.rag.graph.refresh-cron:0 30 3 * * ?}")
    void refreshDaily() {
        try {
            rebuild();
            // 终审修复：空列表传入 foreach 会生成非法 IN () SQL——先判空
            List<Long> dirty = nodeMapper.selectDirtyNodeIds();
            if (dirty != null && !dirty.isEmpty()) {
                nodeMapper.clearDirty(dirty);
            }
            log.info("[GraphKernel] 日级全量重建完成: nodes={}", nodeCount);
        } catch (Exception e) {
            log.warn("[GraphKernel] 日级刷新失败保持现快照: {}", e.getMessage());
        }
    }

    private synchronized void rebuild() {
        SimpleWeightedGraph<Long, DefaultWeightedEdge> g = emptyGraph();
        List<GraphNode> nodes = nodeMapper.selectAllNodes();
        for (GraphNode n : nodes) {
            g.addVertex(n.getId());
        }
        // 终审定稿：单循环同时建边+类型索引（旧骨架双循环残留已清理）
        Map<String, String> typeIdx = new HashMap<>();
        for (GraphEdge e : edgeMapper.selectAllEdges()) {
            // AG 审计实弹修复（2026-09-29）：语义边名称解析可产出 src==dst 自环（实证 id=212
            // SAME_TYPE 自环致 JGraphT addEdge 抛 "loops not allowed" 启动失败）——防御性跳过。
            if (e.getSrcId() != null && e.getSrcId().equals(e.getDstId())) {
                continue;
            }
            if (g.containsVertex(e.getSrcId()) && g.containsVertex(e.getDstId())) {
                DefaultWeightedEdge de = g.addEdge(e.getSrcId(), e.getDstId());
                if (de != null) {
                    g.setEdgeWeight(de, e.getWeight());
                }
                typeIdx.put(e.getSrcId() + ":" + e.getDstId(), e.getEdgeType());
                typeIdx.put(e.getDstId() + ":" + e.getSrcId(), e.getEdgeType());
            }
        }
        this.edgeTypeIndex = typeIdx;
        this.snapshot = g;
        this.nodeCount = nodes.size();
    }

    /** 边类型旁路索引（"src:dst" -> edgeType；终审修复：原骨架 edgeTypes 参数被忽略=结构性谎言） */
    private volatile Map<String, String> edgeTypeIndex = Map.of();

    /** 热路径：1 跳邻域（纯内存，图空返回空集）；边类型经 edgeType(src,dst) 查询 */
    public Map<Long, Double> neighbors(Long id) {
        SimpleWeightedGraph<Long, DefaultWeightedEdge> g = snapshot;
        if (!g.containsVertex(id)) {
            return Map.of();
        }
        Map<Long, Double> out = new LinkedHashMap<>();
        for (DefaultWeightedEdge e : g.edgesOf(id)) {
            Long other = g.getEdgeSource(e).equals(id) ? g.getEdgeTarget(e) : g.getEdgeSource(e);
            out.put(other, g.getEdgeWeight(e));
        }
        return out;
    }

    /** 边类型查询（Expander 按 NEAR/SAME_TYPE 差异化 boost 用；无索引=UNKNOWN） */
    public String edgeType(Long src, Long dst) {
        return edgeTypeIndex.getOrDefault(src + ":" + dst, "UNKNOWN");
    }

    public int nodeCount() { return nodeCount; }
}
