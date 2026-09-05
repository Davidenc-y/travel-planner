package com.travel.planning.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.travel.common.entity.AgentTrace;
import com.travel.common.util.JsonUtils;
import com.travel.planning.map.guard.MapQuotaGuardService;
import com.travel.planning.repository.AgentTraceMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * M11-3：可靠性看板聚合（数据源 t_agent_trace，只读零 DDL）。
 *
 * <p>近 N 天窗口内统计 groundingRate/retentionRate、degraded 计数、
 * 模型×状态分布与节点执行 Top（callPath 内节点计数，M9-3c 观测同源）。</p>
 */
@Slf4j
@Service
public class ReliabilityStatsService {

    private final AgentTraceMapper agentTraceMapper;
    private final MapQuotaGuardService mapQuotaGuardService;

    public ReliabilityStatsService(AgentTraceMapper agentTraceMapper,
                                   MapQuotaGuardService mapQuotaGuardService) {
        this.agentTraceMapper = agentTraceMapper;
        this.mapQuotaGuardService = mapQuotaGuardService;
    }

    /** @return 看板聚合结果（Map 便于前端 recharts 直接消费） */
    public Map<String, Object> stats(int days) {
        int range = days <= 0 ? 7 : Math.min(days, 90);
        LocalDateTime since = LocalDateTime.now().minusDays(range);
        List<AgentTrace> rows = agentTraceMapper.selectList(new QueryWrapper<AgentTrace>()
                .ge("created_at", since));

        List<Double> grounding = new ArrayList<>();
        List<Double> retention = new ArrayList<>();
        Map<String, Map<String, Long>> modelStatus = new LinkedHashMap<>();
        Map<String, Long> nodeCount = new LinkedHashMap<>();
        Map<LocalDate, long[]> dailyTokens = new TreeMap<>();
        Map<String, List<Double>> durationsByModel = new LinkedHashMap<>();
        long tokensTotal = 0;
        int degraded = 0;
        for (AgentTrace t : rows) {
            if (t.getGroundingRate() != null) {
                grounding.add(t.getGroundingRate());
            }
            if (t.getRetentionRate() != null) {
                retention.add(t.getRetentionRate());
            }
            if ("DEGRADED".equalsIgnoreCase(t.getStatus())) {
                degraded++;
            }
            String model = t.getModelName() == null || t.getModelName().isBlank()
                    ? "unknown" : t.getModelName();
            if (t.getTokenTotal() != null && t.getTokenTotal() > 0) {
                tokensTotal += t.getTokenTotal();
                if (t.getCreatedAt() != null) {
                    dailyTokens.computeIfAbsent(t.getCreatedAt().toLocalDate(),
                                    k -> new long[2])[0] += t.getTokenTotal();
                }
            }
            if (t.getCreatedAt() != null) {
                dailyTokens.computeIfAbsent(t.getCreatedAt().toLocalDate(),
                        k -> new long[2])[1]++;
            }
            if (t.getDurationMs() != null && t.getDurationMs() >= 0) {
                durationsByModel.computeIfAbsent(model, k -> new ArrayList<>())
                        .add((double) t.getDurationMs());
            }
            String status = t.getStatus() == null ? "unknown" : t.getStatus();
            modelStatus.computeIfAbsent(model, k -> new LinkedHashMap<>())
                    .merge(status, 1L, Long::sum);
            countNodes(t.getCallPath(), nodeCount);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", range);
        out.put("traceTotal", rows.size());
        out.put("groundingRate", avg(grounding));
        out.put("groundingP25", percentile(grounding, 0.25));
        out.put("groundingP75", percentile(grounding, 0.75));
        out.put("retentionRate", avg(retention));
        out.put("degraded", degraded);
        out.put("tokensTotal", tokensTotal);
        out.put("tokenDailyTrend", buildTokenTrend(dailyTokens, since.toLocalDate()));
        out.put("modelDuration", buildModelDuration(durationsByModel));
        out.put("modelDistribution", toModelRows(modelStatus));
        out.put("topNodes", topNodes(nodeCount, 5));
        out.put("mapQuota", mapQuotaGuardService.usageSnapshot());
        return out;
    }

    /** M14-1c：按日补零的 token 趋势（避免图表断点）。 */
    private static List<Map<String, Object>> buildTokenTrend(
            Map<LocalDate, long[]> daily, LocalDate start) {
        List<Map<String, Object>> rows = new ArrayList<>();
        LocalDate cursor = start;
        LocalDate today = LocalDate.now();
        while (!cursor.isAfter(today)) {
            long[] acc = daily.getOrDefault(cursor, new long[2]);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", cursor.toString());
            row.put("tokens", acc[0]);
            row.put("traces", acc[1]);
            rows.add(row);
            cursor = cursor.plusDays(1);
        }
        return rows;
    }

    /** M14-1c：模型×耗时分布（count/avg/p50/p95/max，单位 ms）。 */
    private static List<Map<String, Object>> buildModelDuration(
            Map<String, List<Double>> durationsByModel) {
        List<Map<String, Object>> rows = new ArrayList<>();
        durationsByModel.forEach((model, values) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("model", model);
            row.put("count", values.size());
            row.put("avgDurationMs", round1(values.stream()
                    .mapToDouble(Double::doubleValue).average().orElse(0)));
            row.put("p50DurationMs", round1(percentile(values, 0.50)));
            row.put("p95DurationMs", round1(percentile(values, 0.95)));
            row.put("maxDurationMs", values.stream()
                    .mapToDouble(Double::doubleValue).max().orElse(0));
            rows.add(row);
        });
        rows.sort(Comparator.comparingLong(r -> -((Number) r.get("count")).longValue()));
        return rows;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static void countNodes(String callPathJson, Map<String, Long> nodeCount) {
        if (callPathJson == null || callPathJson.isBlank()) {
            return;
        }
        try {
            List<?> path = JsonUtils.fromJson(callPathJson, List.class);
            if (path == null) {
                return;
            }
            for (Object o : path) {
                if (o == null) {
                    continue;
                }
                String raw = String.valueOf(o).trim();
                int eq = raw.indexOf('=');
                String node = (eq > 0 ? raw.substring(0, eq) : raw).trim();
                if (node.contains("_") && !node.startsWith("chatConflict")
                        && !node.startsWith("graphFlowWarnings")) {
                    nodeCount.merge(node, 1L, Long::sum);
                }
            }
        } catch (Exception ignored) {
            // callPath 异常不阻断看板
        }
    }

    private static List<Map<String, Object>> toModelRows(
            Map<String, Map<String, Long>> modelStatus) {
        List<Map<String, Object>> rows = new ArrayList<>();
        modelStatus.forEach((model, statuses) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("model", model);
            row.put("statuses", statuses);
            row.put("total", statuses.values().stream().mapToLong(Long::longValue).sum());
            rows.add(row);
        });
        rows.sort(Comparator.comparingLong(r -> -((Number) r.get("total")).longValue()));
        return rows;
    }

    private static List<Map<String, Object>> topNodes(Map<String, Long> nodeCount, int limit) {
        return nodeCount.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("node", e.getKey());
                    m.put("count", e.getValue());
                    return m;
                })
                .toList();
    }

    private static double avg(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private static double percentile(List<Double> values, double p) {
        if (values.isEmpty()) {
            return 0.0;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int idx = (int) Math.round(p * (sorted.size() - 1));
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }
}
