package com.travel.common.dto;

/**
 * M17-1：历史行程结构化事实（画像 Phase A）。
 *
 * <p>存量数据仅有标题（t_travel_profile.history_trips 为标题 JSON 数组），
 * 故除 title 外其余字段可空；未来行程写入侧结构化后逐步补齐。</p>
 *
 * @param title       行程标题（必有）
 * @param destination 目的地（可空——存量无）
 * @param days        天数（可空）
 * @param budget      预算（可空）
 * @param party       同行类型（可空）
 * @param completedAt 完成时间 ISO 文本（可空）
 */
public record ProfileTripFact(
        String title,
        String destination,
        Integer days,
        java.math.BigDecimal budget,
        String party,
        String completedAt) {

    public static ProfileTripFact ofTitle(String title) {
        return new ProfileTripFact(title, null, null, null, null, null);
    }
}
