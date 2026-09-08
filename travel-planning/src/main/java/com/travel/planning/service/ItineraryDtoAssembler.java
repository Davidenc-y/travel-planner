package com.travel.planning.service;

import com.travel.common.dto.ItineraryResponseDTO;
import com.travel.common.dto.ItineraryResponseDTO.AttractionVisit;
import com.travel.common.dto.ItineraryResponseDTO.BudgetBreakdown;
import com.travel.common.dto.ItineraryResponseDTO.DayPlan;
import com.travel.common.dto.ItineraryResponseDTO.MindmapData;
import com.travel.common.entity.Itinerary;
import com.travel.common.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * M10-1c：行程 Entity ↔ DTO 组装器（自 ItineraryService 拆出）。
 *
 * <p>职责：content JSON → dayPlans/budgetBreakdown、mindmapData → MindmapData、
 * estimatedCost 提取、Entity → ResponseDTO。纯解析与组装，无状态。</p>
 */
@Slf4j
@Component
public class ItineraryDtoAssembler {

    /**
     * 从行程 JSON 中提取估算总费用（budgetEstimate.totalCost；失败返回 null）。
     */
    public BigDecimal estimatedCostFrom(String itineraryJson) {
        try {
            Map<String, Object> content = JsonUtils.fromJson(itineraryJson, Map.class);
            if (content == null) {
                return null;
            }
            Object budgetEstimate = content.get("budgetEstimate");
            if (budgetEstimate instanceof Map<?, ?> map) {
                Object totalCost = map.get("totalCost");
                if (totalCost != null) {
                    return new BigDecimal(totalCost.toString());
                }
            }
        } catch (Exception e) {
            log.warn("提取估算费用失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Entity → ResponseDTO（含 dayPlans/mindmap/budgetBreakdown 解析）。
     *
     * @param resumable 权威可续标志（由调用方按状态机口径计算）
     */
    public ItineraryResponseDTO toResponseDTO(Itinerary entity, boolean resumable) {
        ItineraryResponseDTO dto = ItineraryResponseDTO.builder()
                .id(entity.getId())
                .title(entity.getTitle())
                .destination(entity.getDestination())
                .days(entity.getDays())
                .budget(entity.getBudget())
                .estimatedCost(entity.getEstimatedCost())
                .version(entity.getVersion())
                .sessionId(entity.getSessionId())
                .startDate(entity.getStartDate())
                // M28-12：同行人/兴趣（详情页基本信息展示；interests 列为 JSON 数组文本）
                .party(entity.getParty())
                .interests(parseInterestsColumn(entity.getInterests()))
                .generatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null)
                .status(entity.getStatus())
                .resumable(resumable)
                .build();

    // 解析 content JSON → dayPlans + budgetBreakdown
        if (entity.getContent() != null && !entity.getContent().isBlank()) {
            try {
                Map<String, Object> content = JsonUtils.fromJson(entity.getContent(), Map.class);
                if (content != null && content.containsKey("routePlan")) {
                    Object routePlan = content.get("routePlan");
                    if (routePlan instanceof Map<?, ?> routeMap) {
                        Object days = routeMap.get("days");
                        if (days instanceof List<?> dayList) {
                            dto.setDayPlans(parseDayPlans(dayList));
                        }
                    }
                }
                if (content != null && content.containsKey("budgetEstimate")) {
                    dto.setBudgetBreakdown(parseBudgetBreakdown(content.get("budgetEstimate")));
                }
            } catch (Exception e) {
                log.warn("解析 dayPlans 失败: itineraryId={}", entity.getId());
            }
        }

        // 解析 mindmapData JSON → MindmapData
        if (entity.getMindmapData() != null && !entity.getMindmapData().isBlank()) {
            try {
                MindmapData mindmap = JsonUtils.fromJson(entity.getMindmapData(), MindmapData.class);
                dto.setMindmap(mindmap);
            } catch (Exception e) {
                log.warn("解析 mindmap 失败: itineraryId={}", entity.getId());
            }
        }

        return dto;
    }

    /** M28-12：interests 列（JSON 数组文本）容错解析；空/损坏返回 null。 */
    private java.util.List<String> parseInterestsColumn(String interestsJson) {
        if (interestsJson == null || interestsJson.isBlank()) {
            return null;
        }
        try {
            java.util.List<?> list = JsonUtils.fromJson(interestsJson, java.util.List.class);
            if (list == null) {
                return null;
            }
            return list.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList();
        } catch (Exception e) {
            return null;
        }
    }

    /** 将 content.budgetEstimate（Map）解析为 BudgetBreakdown；异常按 null 容错。 */
    @SuppressWarnings("unchecked")
    private BudgetBreakdown parseBudgetBreakdown(Object budgetEstimate) {
        if (!(budgetEstimate instanceof Map)) {
            return null;
        }
        Map<String, Object> map = (Map<String, Object>) budgetEstimate;
        return BudgetBreakdown.builder()
                .ticketCost(toBigDecimal(map.get("ticketCost")))
                .mealCost(toBigDecimal(map.get("mealCost")))
                .transportCost(toBigDecimal(map.get("transportCost")))
                .hotelCost(toBigDecimal(map.get("hotelCost")))
                .otherCost(toBigDecimal(map.get("otherCost")))
                .totalCost(toBigDecimal(map.get("totalCost")))
                .perPersonCost(toBigDecimal(map.get("perPersonCost")))
                .currency(map.get("currency") != null ? map.get("currency").toString() : null)
                .notes(map.get("notes") != null ? map.get("notes").toString() : null)
                .build();
    }

    /** 将 Number/String 安全转 BigDecimal，null 或非法返回 null。 */
    static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        try {
            return new BigDecimal(value.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析每日计划列表 */
    @SuppressWarnings("unchecked")
    private List<DayPlan> parseDayPlans(List<?> dayList) {
        List<DayPlan> result = new ArrayList<>();
        for (Object item : dayList) {
            if (item instanceof Map) {
                Map<String, Object> dayMap = (Map<String, Object>) item;
                DayPlan day = DayPlan.builder()
                        .day((Integer) dayMap.get("day"))
                        .date((String) dayMap.get("date"))
                        .summary((String) dayMap.get("summary"))
                        .transportMode((String) dayMap.get("transportMode"))
                        .hotelSuggestion((String) dayMap.get("hotelSuggestion"))
                        .build();

                Object attractions = dayMap.get("attractions");
                if (attractions instanceof List) {
                    List<AttractionVisit> visits = new ArrayList<>();
                    for (Object attr : (List<?>) attractions) {
                        if (attr instanceof Map) {
                            Map<String, Object> attrMap = (Map<String, Object>) attr;
                            visits.add(AttractionVisit.builder()
                                    .name((String) attrMap.get("name"))
                                    .timeSlot((String) attrMap.get("timeSlot"))
                                    .cost(toBigDecimal(attrMap.get("cost")))
                                    .notes((String) attrMap.get("notes"))
                                    .build());
                        }
                    }
                    day.setAttractions(visits);
                }
                result.add(day);
            }
        }
        return result;
    }
}
