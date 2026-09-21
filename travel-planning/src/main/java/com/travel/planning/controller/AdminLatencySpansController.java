package com.travel.planning.controller;

import com.travel.common.entity.AgentTrace;
import com.travel.common.exception.BusinessException;
import com.travel.common.repository.AgentTraceMapper;
import com.travel.common.result.R;
import com.travel.common.util.JsonUtils;
import com.travel.planning.service.AdminAccessService;
import com.travel.planning.util.AuthUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S-B8：latency-spans 只读聚合端点（§八⑤授权端点，路径独立于 /reliability 前缀）。
 *
 * <p>数据源=t_agent_trace 近 N 天行：ttft P50/P95（B-7 列）、routing 层分布与胜率、
 * hedge.won 率（B-3 列）、检索五段 P95（spans JSON 内 qu/bm25/knn/rerank/gate 段
 * durationMs 解析；route span 为 routing 口径单一事实源，与 B-5b 列填充同源）。
 * 只读、无状态；行数上限 5000 防扫表。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AdminLatencySpansController {

    /** 单次聚合最大行数（防 days 大窗口扫表） */
    static final int MAX_ROWS = 5000;
    /** 五段 span 名与 route 事件名（SpanCollector/HybridRagStrategy/RagDispatcher 挂点同名） */
    private static final List<String> STAGES = List.of("qu", "bm25", "knn", "rerank", "gate");

    private final AgentTraceMapper agentTraceMapper;
    private final AdminAccessService adminAccessService;

    @GetMapping("/api/v1/admin/latency-spans")
    public R<Map<String, Object>> latencySpans(@RequestParam(defaultValue = "7") Integer days) {
        Long userId = AuthUtils.resolveUserId();
        if (!adminAccessService.isAdmin(userId)) {
            throw new BusinessException(40302, "无权访问可靠性看板");
        }
        int d = days == null ? 7 : Math.min(Math.max(days, 1), 30);
        LocalDateTime since = LocalDateTime.now().minusDays(d);
        // 字符串列 QueryWrapper（非 Lambda）：纯单测环境无实体 lambda cache，行为等价
        List<AgentTrace> rows = agentTraceMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<AgentTrace>()
                .select("spans", "ttft_ms", "routing_ms", "hedge_won")
                .ge("created_at", since)
                .last("LIMIT " + MAX_ROWS));

        List<Long> ttfts = new ArrayList<>();
        List<Long> routingMsValues = new ArrayList<>();
        Map<String, Integer> routerCounts = new LinkedHashMap<>();
        int hedgeDecided = 0;
        int hedgeWins = 0;
        Map<String, List<Long>> stages = new LinkedHashMap<>();
        for (String stage : STAGES) {
            stages.put(stage, new ArrayList<>());
        }
        for (AgentTrace row : rows) {
            if (row.getTtftMs() != null) {
                ttfts.add(row.getTtftMs());
            }
            if (row.getHedgeWon() != null) {
                hedgeDecided++;
                if (row.getHedgeWon() == 1) {
                    hedgeWins++;
                }
            }
            if (row.getSpans() != null && !row.getSpans().isBlank()) {
                collectSpanMetrics(row.getSpans(), routerCounts, routingMsValues, stages);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("days", d);
        result.put("samples", rows.size());
        result.put("ttft", latencyBlock(ttfts));
        result.put("routingMs", latencyBlock(routingMsValues));
        Map<String, Object> routing = new LinkedHashMap<>();
        int routerTotal = routerCounts.values().stream().mapToInt(Integer::intValue).sum();
        routing.put("total", routerTotal);
        routing.put("counts", routerCounts);
        result.put("routing", routing);
        Map<String, Object> hedge = new LinkedHashMap<>();
        hedge.put("decided", hedgeDecided);
        hedge.put("wins", hedgeWins);
        hedge.put("winRate", hedgeDecided == 0 ? null : round(hedgeWins * 1.0 / hedgeDecided));
        result.put("hedge", hedge);
        Map<String, Object> stageBlocks = new LinkedHashMap<>();
        for (String stage : STAGES) {
            stageBlocks.put(stage, latencyBlock(stages.get(stage)));
        }
        result.put("stages", stageBlocks);
        return R.ok(result);
    }

    /** 从单行 spans JSON 收集：route 段 router 分布/耗时，五段 durationMs */
    private void collectSpanMetrics(String spansJson, Map<String, Integer> routerCounts,
                                    List<Long> routingMsValues, Map<String, List<Long>> stages) {
        try {
            com.fasterxml.jackson.databind.JsonNode arr = JsonUtils.getMapper().readTree(spansJson);
            if (!arr.isArray()) {
                return;
            }
            for (com.fasterxml.jackson.databind.JsonNode node : arr) {
                String name = node.path("name").asText();
                long duration = node.path("durationMs").asLong(0L);
                if ("route".equals(name)) {
                    String router = node.path("attrs").path("router").asText("unknown");
                    routerCounts.merge(router, 1, Integer::sum);
                    routingMsValues.add(duration);
                } else if (STAGES.contains(name)) {
                    stages.get(name).add(duration);
                }
            }
        } catch (Exception e) {
            log.debug("[latency-spans] spans 解析跳过: {}", e.getMessage());
        }
    }

    /** P50/P95 块（nearest-rank） */
    private Map<String, Object> latencyBlock(List<Long> values) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("count", values.size());
        if (values.isEmpty()) {
            block.put("p50", null);
            block.put("p95", null);
            return block;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        block.put("p50", sorted.get((int) Math.round(0.5 * (sorted.size() - 1))));
        block.put("p95", sorted.get((int) Math.round(0.95 * (sorted.size() - 1))));
        return block;
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
