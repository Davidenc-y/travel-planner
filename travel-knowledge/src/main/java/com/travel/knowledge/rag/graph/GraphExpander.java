package com.travel.knowledge.rag.graph;

import com.travel.knowledge.rag.model.SearchResult;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AG-1c：GraphRAG 邻域扩展器（E-53：热路径纯内存——仅调 GraphKernel.neighborsDetailed()，零 IO 零构建零加锁；
 * AI-2b 起边类型经 NeighborInfo.edgeType 消费=免 edgeType() 旁路二次查询）。
 *
 * <p>expand()：关闭态（enabled=false）方法首行直通返回原列表（E-33 字节等价）；图空/异常 fail-open 直通。
 * 开启态：每候选 1 跳 NEAR 邻域（NEAR 边生成口径=同城 haversine≤2.5km=方案"neighbors∩city"的工程等价，
 * R330 裁定留痕），score=原候选分×boost-weight×边 weight，source='graph' 标记（trace 可观测）；
 * AI-1b 内容化：扩展项经 neighborsDetailed 三源合一填 title/snippet（rerank 可打分=R3 修复本体）；
 * AI-2a 两跳受限 BFS：expand-hops=2 时走受限 BFS（E-56 五防护：路径 visited/total-paths/fanout/
 * 50ms 硬超时/hops 启动校验；hopDecay=0.5 常量不设键），默认 hops=1 走既有单层循环=字节等价（E-33）。
 * docId 去重+expand-top-k 截断。</p>
 */
@Slf4j
@Component
public class GraphExpander {

    /** AI-2a：第 2 跳衰减因子（方案定稿常量不设键） */
    private static final double HOP_DECAY = 0.5;

    private final GraphKernel graphKernel;
    private final GraphExpandProperties properties;

    public GraphExpander(GraphKernel graphKernel, GraphExpandProperties properties) {
        this.graphKernel = graphKernel;
        this.properties = properties;
    }

    /** AI-2a：hops 启动校验（E-56 防护五：仅允许 1/2，其他值启动即拒——防误配致无限跳深） */
    @PostConstruct
    void validateHops() {
        int hops = properties.getExpandHops();
        if (hops < 1 || hops > 2) {
            throw new IllegalStateException("[GraphExpander] expand-hops 仅支持 1/2，当前=" + hops);
        }
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
            if (properties.getExpandHops() == 2) {
                // AI-2a：两跳受限 BFS——E-56 五防护（路径 visited/total-paths/fanout/50ms 硬超时/hops 启动校验）
                long deadline = System.currentTimeMillis() + properties.getExpandTimeoutMs();
                int paths = 0;
                int fanout = properties.getExpandFanout();
                int totalPaths = properties.getExpandTotalPaths();
                Set<String> visitedPaths = new HashSet<>();
                for (SearchResult c : candidates) {
                    if (quota <= 0 || paths >= totalPaths || System.currentTimeMillis() > deadline) {
                        break;
                    }
                    Long nodeId = parseNodeId(c.getDocId());
                    if (nodeId == null) {
                        continue;
                    }
                    // 第 1 跳（扇出 fanout 截断）→ 产出扩展项
                    int hop1 = 0;
                    for (Map.Entry<Long, GraphKernel.NeighborInfo> info1 : graphKernel.neighborsDetailed(nodeId).entrySet()) {
                        if (hop1 >= fanout || quota <= 0 || paths >= totalPaths
                                || System.currentTimeMillis() > deadline) {
                            break;
                        }
                        // AI-2b 语义边路由：expandEdgeTypes 白名单（默认 [NEAR]=同城 2.5km 口径零变化）
                        if (!properties.getExpandEdgeTypes().contains(info1.getValue().edgeType())) {
                            continue;
                        }
                        String docId1 = String.valueOf(info1.getKey());
                        if (seen.contains(docId1)) {
                            continue;
                        }
                        seen.add(docId1);
                        pool.add(SearchResult.builder()
                                .docId(docId1)
                                .score(c.getScore() * properties.getBoostWeight() * info1.getValue().weight())
                                .title(info1.getValue().name())
                                .snippet(info1.getValue().city() + "·" + info1.getValue().nodeType())
                                .source("graph")
                                .build());
                        quota--;
                        hop1++;
                        paths++;
                        // 第 2 跳（同扇出+路径 visited "a:b:c"）：score=c.score×boost×w1×hopDecay×w2
                        int hop2 = 0;
                        for (Map.Entry<Long, GraphKernel.NeighborInfo> info2 : graphKernel.neighborsDetailed(info1.getKey()).entrySet()) {
                            if (hop2 >= fanout || quota <= 0 || paths >= totalPaths
                                    || System.currentTimeMillis() > deadline) {
                                break;
                            }
                            String path = c.getDocId() + ":" + info1.getKey() + ":" + info2.getKey();
                            if (visitedPaths.contains(path)) {
                                continue;
                            }
                            visitedPaths.add(path);
                            if (!properties.getExpandEdgeTypes().contains(info2.getValue().edgeType())) {
                                continue;
                            }
                            String docId2 = String.valueOf(info2.getKey());
                            if (seen.contains(docId2)) {
                                continue;
                            }
                            seen.add(docId2);
                            pool.add(SearchResult.builder()
                                    .docId(docId2)
                                    .score(c.getScore() * properties.getBoostWeight()
                                            * info1.getValue().weight() * HOP_DECAY * info2.getValue().weight())
                                    .title(info2.getValue().name())
                                    .snippet(info2.getValue().city() + "·" + info2.getValue().nodeType())
                                    .source("graph")
                                    .build());
                            quota--;
                            hop2++;
                        }
                    }
                }
                if (System.currentTimeMillis() > deadline) {
                    log.warn("[GraphExpander] 50ms 硬超时截断: paths={}, pool={}", paths, pool.size());
                }
            } else {
                // hops==1（默认）：既有单层循环逐字保留（E-33 字节等价路径）
                for (SearchResult c : candidates) {
                    if (quota <= 0) {
                        break;
                    }
                    Long nodeId = parseNodeId(c.getDocId());
                    if (nodeId == null) {
                        continue;
                    }
                    for (Map.Entry<Long, GraphKernel.NeighborInfo> info : graphKernel.neighborsDetailed(nodeId).entrySet()) {
                        if (quota <= 0) {
                            break;
                        }
                        // AI-2b 语义边路由：expandEdgeTypes 白名单（默认 [NEAR]=同城 2.5km 口径零变化）
                        if (!properties.getExpandEdgeTypes().contains(info.getValue().edgeType())) {
                            continue;
                        }
                        String docId = String.valueOf(info.getKey());
                        if (seen.contains(docId)) {
                            continue;
                        }
                        seen.add(docId);
                        // AI-1b 内容化：title/snippet 取 NeighborInfo 节点内容三源（rerank 可打分）
                        pool.add(SearchResult.builder()
                                .docId(docId)
                                .score(c.getScore() * properties.getBoostWeight() * info.getValue().weight())
                                .title(info.getValue().name())
                                .snippet(info.getValue().city() + "·" + info.getValue().nodeType())
                                .source("graph")
                                .build());
                        quota--;
                    }
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
