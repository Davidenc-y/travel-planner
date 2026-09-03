package com.travel.common.dto;

import java.util.List;

/**
 * U1：个人中心使用统计（聚合自 t_agent_trace，口径见 UserUsageStatsService）。
 *
 * @param totalTokens       累计 Token 数（全量 Σtoken_total）
 * @param peakDayTokens     峰值 Token 数（单日最大消耗）
 * @param longestTurnMs     最长聊天时长（单轮 request_id 起止跨度最大值，毫秒）
 * @param currentStreakDays 当前连续天数（截至今天/昨天的连续活跃自然日数）
 * @param longestStreakDays 最长连续天数（历史最长连续活跃自然日数）
 * @param daily             近 365 天逐日 Token 总量（热力图，date=yyyy-MM-dd）
 * @param trend             近 range 天逐日×模型 Token（趋势图，缺失日不补零，前端补）
 * @param modelUsage        近 range 天按模型 Token 总量（用量环形图）
 */
public record UsageStatsDTO(
        long totalTokens,
        long peakDayTokens,
        long longestTurnMs,
        int currentStreakDays,
        int longestStreakDays,
        List<DailyToken> daily,
        List<DailyModelToken> trend,
        List<ModelUsage> modelUsage) {

    /** 单日 Token 总量 */
    public record DailyToken(String date, long tokens) {
    }

    /** 单日单模型 Token 量 */
    public record DailyModelToken(String date, String model, long tokens) {
    }

    /** 范围内单模型 Token 总量 */
    public record ModelUsage(String model, long tokens) {
    }
}
