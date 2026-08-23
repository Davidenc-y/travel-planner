package com.travel.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 行程生成响应 DTO
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ItineraryResponseDTO {

    private Long id;
    private String title;
    private String destination;
    private Integer days;
    private List<DayPlan> dayPlans;
    private BigDecimal estimatedCost;
    private BudgetBreakdown budgetBreakdown;
    private MindmapData mindmap;
    private String generatedAt;
    /** M4-9：行程状态（GENERATING/FAILED/GENERATED/CONFIRMED/ARCHIVED，前端徽标用） */
    private String status;

    /**
     * 每日计划
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DayPlan {
        private Integer day;
        private String date;
        private String summary;
        private List<AttractionVisit> attractions;
        private String transportMode;
        private String hotelSuggestion;
    }

    /**
     * 景点访问
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AttractionVisit {
        private String name;
        private String timeSlot;
        private BigDecimal cost;
        private String notes;
    }

    /**
     * 预算分配明细 —— 由 BudgetEstimationAgent 输出的 budgetEstimate 透出，
     * 供前端展示"预算用在哪里"（M2-5 输出优化）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BudgetBreakdown {
        private BigDecimal ticketCost;
        private BigDecimal mealCost;
        private BigDecimal transportCost;
        private BigDecimal hotelCost;
        private BigDecimal otherCost;
        private BigDecimal totalCost;
        private BigDecimal perPersonCost;
        private String currency;
        private String notes;
    }

    /**
     * 思维导图数据
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MindmapData {
        private String title;
        private String destination;
        private String days;
        private String budget;
        private List<Section> sections;
    }

    /**
     * 思维导图分区
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Section {
        private String title;
        private List<String> items;
    }
}
