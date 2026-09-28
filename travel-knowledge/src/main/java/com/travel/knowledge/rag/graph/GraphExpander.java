package com.travel.knowledge.rag.graph;

import com.travel.knowledge.rag.model.SearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AG-1c：GraphRAG 邻域扩展器（E-53：热路径纯内存——仅调 GraphKernel.neighbors()/edgeType()，零 IO 零构建零加锁）。
 *
 * <p>expand()：关闭态（enabled=false）方法首行直通返回原列表（E-33 字节等价）；图空/异常 fail-open 直通。
 * 开启态：每候选 1 跳 NEAR 邻域（NEAR 边生成口径=同城 haversine≤2.5km=方案"neighbors∩city"的工程等价，
 * R330 裁定留痕），score=原候选分×boost-weight×边 weight，source='graph' 标记（trace 可观测）；
 * docId 去重+expand-top-k 截断。邻域项骨架最小面=docId+score+source（neighbors() 不携带节点属性，
 * title/snippet 留 null，开启态 rerank/注入兼容性归审计窗决策门验证）。</p>
 */
@Slf4j
@Component
public class GraphExpander {

    private final GraphKernel graphKernel;
    private final GraphExpandProperties properties;

    public GraphExpander(GraphKernel graphKernel, GraphExpandProperties properties) {
        this.graphKernel = graphKernel;
        this.properties = properties;
    }

    /**
     * 候选池邻域扩展（关闭态首行直通=原列表引用；图空/异常 fail-open）
     */
    public List<SearchResult> expand(List<SearchResult> candidates) {
        // E-33 关闭态：方法首行直通返回原列表（字节等价）
        if (!properties.isEnabled() || candidates == null || candidates.isEmpty()) {
            return candidates;
        }
        try {
            // 图空 fail-open（启动加载失败=空图，静默直通）
            if (graphKernel.nodeCount() == 0) {
                return candidates;
            }
            List<SearchResult> pool = new ArrayList<>(candidates);
            Set<String> seen = new HashSet<>();
            for (SearchResult c : candidates) {
                if (c.getDocId() != null) {
                    seen.add(c.getDocId());
                }
            }
            int quota = properties.getExpandTopK();
            for (SearchResult c : candidates) {
                if (quota <= 0) {
                    break;
                }
                Long nodeId = parseNodeId(c.getDocId());
                if (nodeId == null) {
                    continue;
                }
                for (Map.Entry<Long, Double> nb : graphKernel.neighbors(nodeId).entrySet()) {
                    if (quota <= 0) {
                        break;
                    }
                    // 同城过滤（∩city 工程等价）：仅 NEAR 边邻居进池（NEAR 生成口径=同城 2.5km 距离衰减）
                    if (!"NEAR".equals(graphKernel.edgeType(nodeId, nb.getKey()))) {
                        continue;
                    }
                    String docId = String.valueOf(nb.getKey());
                    if (seen.contains(docId)) {
                        continue;
                    }
                    seen.add(docId);
                    pool.add(SearchResult.builder()
                            .docId(docId)
                            .score(c.getScore() * properties.getBoostWeight() * nb.getValue())
                            .source("graph")
                            .build());
                    quota--;
                }
            }
            return pool;
        } catch (Exception e) {
            log.warn("[GraphExpander] expand 失败 fail-open 直通: {}", e.getMessage());
            return candidates;
        }
    }

    /** docId→图节点 id 解析（非数字 docId=非图节点，跳过） */
    private Long parseNodeId(String docId) {
        if (docId == null || docId.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(docId);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
