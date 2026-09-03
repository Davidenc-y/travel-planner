package com.travel.planning.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.travel.common.dto.UsageStatsDTO;
import com.travel.common.entity.AgentTrace;
import com.travel.planning.repository.AgentTraceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * U1：个人中心使用统计（数据源 t_agent_trace，方案 B：零 DDL 聚合）。
 *
 * <p>方案取舍（详见 docs/front_design/records/U1-个人中心使用统计-工作记录.md）：
 * A. 聚合 t_chat_message（无 model 列，需 DDL+管线改造）；
 * B. 聚合 t_agent_trace（零 DDL，天然含 user_id/model_name/token_total/duration_ms/start_time）——【选定】；
 * C. 新建日用量汇总表+管线埋点（改动最大，过度设计）。</p>
 *
 * <p>口径：
 * 累计 Token = Σtoken_total；峰值 = 单日最大消耗；最长聊天时长 = 单轮（request_id）
 * 起止跨度最大值；连续天数 = 有调用记录的自然日（今天缺失时从昨天起算，今天尚未断签）。
 * trace 为异步批量落库，统计属近实时最终一致；轻角色调用计入，口径为 LLM 总用量。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserUsageStatsService {

    private static final int HEATMAP_DAYS = 365;

    private final AgentTraceMapper agentTraceMapper;

    public UsageStatsDTO getUsageStats(Long userId, int rangeDays) {
        LocalDate today = LocalDate.now();
        LocalDate rangeStart = today.minusDays(rangeDays - 1L);
        LocalDate heatmapStart = today.minusDays(HEATMAP_DAYS - 1L);

        // Q1：逐日 Token 总量（全量历史）——热力图/累计/峰值/连续天共用一次查询
        QueryWrapper<AgentTrace> dailyQ = new QueryWrapper<>();
        dailyQ.select("DATE(start_time) AS d", "COALESCE(SUM(token_total), 0) AS t")
                .eq("user_id", userId)
                .isNotNull("start_time")
                .groupBy("d");
        TreeMap<LocalDate, Long> dailyMap = new TreeMap<>();
        for (Map<String, Object> row : agentTraceMapper.selectMaps(dailyQ)) {
            LocalDate d = toLocalDate(row.get("d"));
            if (d != null) {
                dailyMap.merge(d, toLong(row.get("t")), Long::sum);
            }
        }
        long totalTokens = dailyMap.values().stream().mapToLong(Long::longValue).sum();
        long peakDayTokens = dailyMap.values().stream().mapToLong(Long::longValue).max().orElse(0L);
        int[] streaks = computeStreaks(new ArrayList<>(dailyMap.keySet()), today);

        List<UsageStatsDTO.DailyToken> daily = dailyMap.tailMap(heatmapStart).entrySet().stream()
                .map(e -> new UsageStatsDTO.DailyToken(e.getKey().toString(), e.getValue()))
                .toList();

        // Q2：范围内逐日×模型（趋势图）
        QueryWrapper<AgentTrace> trendQ = new QueryWrapper<>();
        trendQ.select("DATE(start_time) AS d", "model_name AS m", "COALESCE(SUM(token_total), 0) AS t")
                .eq("user_id", userId)
                .ge("start_time", rangeStart.atStartOfDay())
                .isNotNull("start_time")
                .groupBy("d", "m")
                .orderByAsc("d");
        List<UsageStatsDTO.DailyModelToken> trend = agentTraceMapper.selectMaps(trendQ).stream()
                .map(row -> new UsageStatsDTO.DailyModelToken(
                        dateStr(row.get("d")),
                        str(row.get("m")),
                        toLong(row.get("t"))))
                .toList();

        // Q3：范围内按模型（用量环形图）
        QueryWrapper<AgentTrace> modelQ = new QueryWrapper<>();
        modelQ.select("model_name AS m", "COALESCE(SUM(token_total), 0) AS t")
                .eq("user_id", userId)
                .ge("start_time", rangeStart.atStartOfDay())
                .groupBy("m");
        List<UsageStatsDTO.ModelUsage> modelUsage = agentTraceMapper.selectMaps(modelQ).stream()
                .map(row -> new UsageStatsDTO.ModelUsage(str(row.get("m")), toLong(row.get("t"))))
                .sorted((a, b) -> Long.compare(b.tokens(), a.tokens()))
                .toList();

        // Q4：单轮（request_id）起止跨度 → 最长聊天时长
        QueryWrapper<AgentTrace> turnQ = new QueryWrapper<>();
        turnQ.select("request_id AS r", "MIN(start_time) AS s", "MAX(end_time) AS e")
                .eq("user_id", userId)
                .isNotNull("start_time")
                .groupBy("r");
        long longestTurnMs = 0L;
        for (Map<String, Object> row : agentTraceMapper.selectMaps(turnQ)) {
            LocalDateTime s = toLocalDateTime(row.get("s"));
            LocalDateTime e = toLocalDateTime(row.get("e"));
            if (s != null && e != null && !e.isBefore(s)) {
                longestTurnMs = Math.max(longestTurnMs, Duration.between(s, e).toMillis());
            }
        }

        return new UsageStatsDTO(
                totalTokens, peakDayTokens, longestTurnMs,
                streaks[0], streaks[1],
                daily, trend, modelUsage);
    }

    /**
     * 连续活跃天数（纯函数，供单测）：days 为活跃自然日（**任意顺序**，方法内部排序——
     * U1 单测发现的契约缺陷：原实现假设升序，降序输入会把 longest 全部算成 1）。
     * current：今天活跃从今天回数，否则从昨天回数（今天尚未断签）；
     * longest：升序遍历数连续段最大长度。
     */
    static int[] computeStreaks(List<LocalDate> days, LocalDate today) {
        if (days == null || days.isEmpty()) {
            return new int[]{0, 0};
        }
        Set<LocalDate> set = new HashSet<>(days);
        LocalDate cursor = set.contains(today) ? today : today.minusDays(1);
        int current = 0;
        while (set.contains(cursor)) {
            current++;
            cursor = cursor.minusDays(1);
        }
        List<LocalDate> sorted = new ArrayList<>(days);
        java.util.Collections.sort(sorted);
        int longest = 0;
        int run = 0;
        LocalDate prev = null;
        for (LocalDate d : sorted) {
            run = (prev != null && d.equals(prev.plusDays(1))) ? run + 1 : 1;
            longest = Math.max(longest, run);
            prev = d;
        }
        return new int[]{current, longest};
    }

    private static LocalDate toLocalDate(Object o) {
        if (o instanceof Date d) {
            return d.toLocalDate();
        }
        if (o instanceof LocalDate d) {
            return d;
        }
        if (o instanceof LocalDateTime d) {
            return d.toLocalDate();
        }
        if (o instanceof String s && !s.isBlank()) {
            return LocalDate.parse(s.substring(0, 10));
        }
        return null;
    }

    private static LocalDateTime toLocalDateTime(Object o) {
        if (o instanceof Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (o instanceof LocalDateTime d) {
            return d;
        }
        if (o instanceof String s && !s.isBlank()) {
            return LocalDateTime.parse(s.replace(' ', 'T').substring(0, 19));
        }
        return null;
    }

    private static long toLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private static String str(Object o) {
        return o == null ? "unknown" : String.valueOf(o);
    }

    private static String dateStr(Object o) {
        LocalDate d = toLocalDate(o);
        return d == null ? null : d.toString();
    }
}
