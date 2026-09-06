package com.travel.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * M17-2：用户行为画像实体（t_user_behavior_profile）。
 *
 * <p><b>派生聚合表</b>：由既有行为数据（t_agent_trace / t_chat_session / t_itinerary / 
 * t_itinerary_version）滚动窗口聚合而来，可随时 TRUNCATE 全量重算——与显式画像
 * {@code t_travel_profile}（用户/Agent 写入的事实数据）分离，更新策略与失效语义互不影响。</p>
 */
@Data
@TableName("t_user_behavior_profile")
public class UserBehaviorProfile {

    @TableId(type = IdType.INPUT)
    private Long userId;

    /** 24 维小时直方图 JSON [0..23]（窗口内 trace 开始时间计数） */
    private String activeHours;

    /** 周均规划次数 */
    private BigDecimal planWeeklyAvg;

    /** 窗口内行程预算分位 P25/P50/P75 */
    private BigDecimal budgetP25;
    private BigDecimal budgetP50;
    private BigDecimal budgetP75;

    /** 目的地频次 JSON [{"city":..,"cnt":..,"lastAt":..}]（最多 8 项） */
    private String destFreq;

    /** 同行类型众数（家庭/情侣/朋友/独行…）与置信度（0-100） */
    private String partyMode;
    private Integer partyConfidence;

    /** 平均每行程版本增量（REFINE 倾向；versions-1 的均值，≥0） */
    private BigDecimal refineAvg;

    /** 窗口内行程数 / 会话数（可靠性门槛输入） */
    private Integer tripCount;
    private Integer sessionCount;

    /** 统计窗口（天） */
    private Integer statsWindowDays;

    /** 最近重算时间 */
    private LocalDateTime computedAt;
}
