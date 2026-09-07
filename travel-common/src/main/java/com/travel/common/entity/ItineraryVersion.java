package com.travel.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * M11-1：行程历史版本快照（t_itinerary_version）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_itinerary_version")
public class ItineraryVersion extends BaseEntity {

    private Long itineraryId;

    /** 版本号（从 1 递增） */
    private Integer version;

    /** 行程内容 JSON 快照 */
    private String content;

    /** 思维导图 JSON 快照 */
    private String mindmapData;

    /** 估算费用快照 */
    private BigDecimal estimatedCost;

    /** 上次→本次 diff JSON（四列表） */
    private String versionDiff;
    /** M28-7：约束快照（版本切换恢复 budget/days/start_date 的唯一来源；存量行为 NULL） */
    private Integer days;
    private BigDecimal budget;
    private String startDate;

    /** t_itinerary_version 无 updated_at 列：覆盖父类字段避免 MyBatis-Plus 查询报错 */
    @TableField(exist = false)
    private LocalDateTime updatedAt;
}
