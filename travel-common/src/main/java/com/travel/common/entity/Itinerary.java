package com.travel.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 行程实体（t_itinerary）
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_itinerary")
public class Itinerary extends BaseEntity {

    /** 用户 ID */
    private Long userId;

    /** 目的地 */
    private String destination;

    /** 天数 */
    private Integer days;

    /** 预算 */
    private BigDecimal budget;

    /** JSON: 兴趣数组 */
    private String interests;

    /** 出行人员: 独行/情侣/家庭/朋友 */
    private String party;

    /** 开始日期 yyyy-MM-dd */
    private String startDate;

    /** 状态: DRAFT/GENERATED/CONFIRMED/ARCHIVED */
    private String status;

    /** 行程标题 */
    private String title;

    /** JSON: 行程详情 */
    private String content;

    /** JSON: 思维导图 */
    private String mindmapData;

    /** 估算总费用 */
    private BigDecimal estimatedCost;

    /** 幂等键 UUID */
    private String clientRequestId;
}
