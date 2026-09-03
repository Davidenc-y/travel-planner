package com.travel.planning.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * M8-3：行程冲突确定性校验配置。
 *
 * <p>对应 yml：{@code travel.itinerary.conflict-check.*}。
 * {@code enabled=false} 时 TravelWorkflowBuilder 不挂 conflict_check 节点，
 * 图结构回到 M8-3 之前（snapshot_route → budget_estimation 直连）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.itinerary.conflict-check")
public class ItineraryConflictCheckProperties {

    /** 冲突校验开关（false = 回到现图行为） */
    private boolean enabled = true;

    /** 时间冲突重试上限（独立于预算 retryCount） */
    private int maxRouteRetry = 2;

    /** DurationCapacityRule 可用时间窗开始 */
    private String dayStart = "08:00";

    /** DurationCapacityRule 可用时间窗结束 */
    private String dayEnd = "21:00";
}
