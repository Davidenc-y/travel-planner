package com.travel.planning.service.monitoring;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.travel.common.cache.RedisResultCache;
import com.travel.common.entity.AgentTrace;
import com.travel.common.util.JsonUtils;
import com.travel.planning.map.guard.MapQuotaGuardService;
import com.travel.common.repository.AgentTraceMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
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
 *
 * <p>AX-2：聚合下推——标量五合一/按日趋势/模型分布/精确百分位（窗口函数，stats round 式
 * 秩公式=P0-AX⑴ 逐字复刻）改由 SQL 聚合返回，消除窗口内全量行搬运（AW 审计实测 2.7s
 * 且随表线性恶化）；topNodes 保留 call_path 有界采样（键 travel.trace.topnodes-sample
 * 默认 20000，0=不限=AW 前精确行为，P0-AX⑶）。</p>
 */
@Slf4j
@Service
public class ReliabilityStatsService {

    private final AgentTraceMapper agentTraceMapper;
    private final MapQuotaGuardService mapQuotaGuardService;
    private final int topNodesSample;
    private final RedisResultCache statsCache;

    public ReliabilityStatsService(AgentTraceMapper agentTraceMapper,
                                   MapQuotaGuardService mapQuotaGuardService,
                                   @Value("${travel.trace.topnodes-sample:20000}") int topNodesSample,
                                   StringRedisTemplate redisTemplate) {
        this.agentTraceMapper = agentTraceMapper;
        this.mapQuotaGuardService = mapQuotaGuardService;
        this.topNodesSample = topNodesSample;
        this.statsCache = new RedisResultCache(redisTemplate, "travel:cache:");
    }

    /** @return 看板聚合结果（Map 便于前端 recharts 直接消费）——BB-1：Redis 结果缓存 120s TTL+抖动，fail-open 降级直查。 */
    public Map<String, Object> stats(int days) {
        return statsCache.computeIfAbsent("stats:" + days, Map.class, Duration.ofSeconds(120),
                () -> computeStats(days));
    }

    private Map<String, Object> computeStats(int days) {
        int range = days <= 0 ? 7 : Math.min(days, 90);
        LocalDateTime since = LocalDateTime.now().minusDays(range);
        Map<String, Object> agg = agentTraceMapper.selectStatsAggregateSince(since);

        Map<LocalDate, long[]> dailyTokens = new TreeMap<>();
        for (Map<String, Object> row : agentTraceMapper.selectDailyTokensSince(since)) {
            Object d = row.get("day");
            // mysql-connector-j 对 DATE 列的 map 返回随版本在 java.sql.Date/LocalDate 间漂移
            LocalDate day = d instanceof java.sql.Date ? ((java.sql.Date) d).toLocalDate()
                    : d instanceof LocalDate ? (LocalDate) d : LocalDate.parse(String.valueOf(d));
            long[] acc = dailyTokens.computeIfAbsent(day, k -> new long[2]);
            acc[0] += ((Number) row.get("tokens")).longValue();
            acc[1] += ((Number) row.get("traces")).longValue();
        }

        Map<String, Map<String, Long>> modelStatus = new LinkedHashMap<>();
        for (Map<String, Object> row : agentTraceMapper.selectModelStatusSince(since)) {
            String model = normalizeModel((String) row.get("modelName"));
            String status = row.get("status") == null ? "unknown" : String.valueOf(row.get("status"));
            modelStatus.computeIfAbsent(model, k -> new LinkedHashMap<>())
                    .merge(status, ((Number) row.get("cnt")).longValue(), Long::sum);
        }

        // 百分位行（至多 2 行）：双行小=P25 大=P75（秩公式 p75 位 >= p25 位）；单行两百分位同值；零行=0.0
        List<Map<String, Object>> groundingPct = agentTraceMapper.selectGroundingPercentilesSince(since);
        double groundingP25 = 0.0;
        double groundingP75 = 0.0;
        if (groundingPct.size() == 1) {
            double v = ((Number) groundingPct.get(0).get("val")).doubleValue();
            groundingP25 = v;
            groundingP75 = v;
        } else if (groundingPct.size() >= 2) {
            double v1 = ((Number) groundingPct.get(0).get("val")).doubleValue();
            double v2 = ((Number) groundingPct.get(1).get("val")).doubleValue();
            groundingP25 = Math.min(v1, v2);
            groundingP75 = Math.max(v1, v2);
        }

        // AX 审计直修：duration 分区窗口函数在 10 万行非空形态下需全量物化排序（实测
        // 2.5s/查询=净回归）——duration 百分位改回单列 (model,duration) 拉取 + Java
        // percentile 静态法（854ms 实测；秩公式两式对拍已锁定，AW-5 行为同源）
        Map<String, List<Double>> durationsByModel = new LinkedHashMap<>();
        for (Map<String, Object> row : agentTraceMapper.selectModelDurationsByCreatedAtSince(since)) {
            durationsByModel.computeIfAbsent(normalizeModel((String) row.get("modelName")),
                            k -> new ArrayList<>())
                    .add(((Number) row.get("durationMs")).doubleValue());
        }

        Map<String, Long> nodeCount = new LinkedHashMap<>();
        QueryWrapper<AgentTrace> topNodesWrapper = new QueryWrapper<AgentTrace>()
                .select("call_path")
                .ge("created_at", since)
                .orderByDesc("created_at");
        if (topNodesSample > 0) {
            // P0-AX⑶：>0=最近 N 行有界采样；0=不加 LIMIT=AW 前精确行为（countNodes 全量消费）
            topNodesWrapper.last("LIMIT " + topNodesSample);
        }
        List<AgentTrace> callPathRows = agentTraceMapper.selectList(topNodesWrapper);
        for (AgentTrace t : callPathRows) {
            if (t == null) {
                // S-B8 教训：MyBatis 将全 NULL 部分列选择行映射为 null 列表元素
                continue;
            }
            countNodes(t.getCallPath(), nodeCount);
        }

        Map<String, Long> focusCount = new LinkedHashMap<>();
        long focusDetour = longOf(agg.get("focusDetour"));
        long focusMainline = longOf(agg.get("focusMainline"));
        // 形状忠实复刻：旧 Java 仅在计数 >=1 时放键（无匹配=空 map，非 {DETOUR:0,MAINLINE:0}）
        if (focusDetour > 0) {
            focusCount.put("DETOUR", focusDetour);
        }
        if (focusMainline > 0) {
            focusCount.put("MAINLINE", focusMainline);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", range);
        out.put("traceTotal", longOf(agg.get("traceTotal")));
        out.put("groundingRate", doubleOf(agg.get("groundingAvg")));
        out.put("groundingP25", groundingP25);
        out.put("groundingP75", groundingP75);
        out.put("retentionRate", doubleOf(agg.get("retentionAvg")));
        out.put("degraded", longOf(agg.get("degraded")));
        out.put("tokensTotal", longOf(agg.get("tokensTotal")));
        out.put("tokenDailyTrend", buildTokenTrend(dailyTokens, since.toLocalDate()));
        out.put("modelDuration", buildModelDuration(durationsByModel));
        out.put("modelDistribution", toModelRows(modelStatus));
        out.put("topNodes", topNodes(nodeCount, 5));
        out.put("mapQuota", mapQuotaGuardService.usageSnapshot());
        // M27（S5/E3 观测支撑）：看板焦点观测卡数据（DETOUR 样本/主线分布/隔离生效数）
        out.put("focusDistribution", focusCount);
        out.put("detourIsolated", longOf(agg.get("detourIsolated")));
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

    private static String normalizeModel(String model) {
        return model == null || model.isBlank() ? "unknown" : model;
    }

    private static long longOf(Object v) {
        return v == null ? 0L : ((Number) v).longValue();
    }

    private static double doubleOf(Object v) {
        return v == null ? 0.0 : ((Number) v).doubleValue();
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
