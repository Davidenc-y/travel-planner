package com.travel.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

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
}
