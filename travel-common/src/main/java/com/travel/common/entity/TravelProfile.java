package com.travel.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户旅游画像实体（t_travel_profile）
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_travel_profile")
public class TravelProfile extends BaseEntity {

    /** 用户 ID */
    private Long userId;

    /** JSON: 常去目的地 ["北京","上海"] */
    private String preferredDestinations;

    /** JSON: 偏好兴趣 ["文化","美食"] */
    private String preferredInterests;

    /** 预算区间: 3000-5000 */
    private String budgetRange;

    /** 出行风格: ECONOMY/COMFORT/LUXURY */
    private String travelStyle;

    /** JSON: 历史行程摘要 */
    private String historyTrips;

    /** 累计行程数 */
    private Integer totalTrips;
}
