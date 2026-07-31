package com.travel.common.dto;

import lombok.Builder;
import lombok.Data;

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
public class ItineraryResponseDTO {

    private Long id;
    private String title;
    private String destination;
    private Integer days;
    private List<DayPlan> dayPlans;
    private BigDecimal estimatedCost;
    private MindmapData mindmap;
    private String generatedAt;

    /**
     * 每日计划
     */
    @Data
    @Builder
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
    public static class AttractionVisit {
        private String name;
        private String timeSlot;
        private BigDecimal cost;
        private String notes;
    }

    /**
     * 思维导图数据
     */
    @Data
    @Builder
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
    public static class Section {
        private String title;
        private List<String> items;
    }
}
