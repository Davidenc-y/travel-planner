package com.travel.planning.service;

import com.travel.common.repository.AgentTraceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * E-5b：慢轮次端点服务（纯读聚合）——t_agent_trace 按 model 分组 P50/P95/avg/count
 * + Top 慢轮次明细 10 条。
 *
 * <p>百分位在 Java 侧计算（MySQL 8 无 PERCENTILE_CONT）：最近邻秩法
 * （sorted[ceil(p*n)-1]，n=1 时取唯一值）。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Service
@RequiredArgsConstructor
public class TurnLatencyService {

    private final AgentTraceMapper agentTraceMapper;

    public Map<String, Object> turnLatency(int days) {
        int windowDays = days <= 0 ? 7 : days;
        LocalDateTime since = LocalDateTime.now().minusDays(windowDays);

        List<Map<String, Object>> rows = agentTraceMapper.selectModelDurationsSince(since);
        Map<String, List<Long>> byModel = new TreeMap<>();
        for (Map<String, Object> row : rows) {
            String model = String.valueOf(row.get("modelName"));
            long duration = ((Number) row.get("durationMs")).longValue();
            byModel.computeIfAbsent(model, k -> new ArrayList<>()).add(duration);
        }

        List<Map<String, Object>> models = new ArrayList<>();
        for (Map.Entry<String, List<Long>> entry : byModel.entrySet()) {
            List<Long> sorted = new ArrayList<>(entry.getValue());
            sorted.sort(Long::compare);
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("model", entry.getKey());
            stat.put("count", sorted.size());
            stat.put("avgMs", round1(sorted.stream().mapToLong(Long::longValue).average().orElse(0)));
            stat.put("p50Ms", percentile(sorted, 50));
            stat.put("p95Ms", percentile(sorted, 95));
            models.add(stat);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("windowDays", windowDays);
        result.put("totalTurns", rows.size());
        result.put("byModel", models);
        result.put("topSlow", agentTraceMapper.selectTopSlowTurns(since));
        return result;
    }

    /** 最近邻秩百分位：sorted 升序，取 sorted[ceil(p*n/100)-1]。 */
    static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0L;
        }
        int index = (int) Math.ceil(p * sorted.size() / 100.0) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
