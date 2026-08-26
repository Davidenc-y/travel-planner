package com.travel.planning.service;

import com.travel.common.dto.ItineraryResponseDTO;

/**
 * M6-15 Item 4：把 ItineraryResponseDTO 转成可流式展示的文本。
 */
final class ItineraryStreamTextBuilder {

    private ItineraryStreamTextBuilder() {
    }

    static String build(ItineraryResponseDTO dto) {
        if (dto == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (dto.getTitle() != null && !dto.getTitle().isBlank()) {
            sb.append("【").append(dto.getTitle()).append("】\n");
        }
        sb.append("目的地：").append(dto.getDestination())
                .append("（").append(dto.getDays()).append("天）\n");
        if (dto.getDayPlans() != null) {
            for (ItineraryResponseDTO.DayPlan dp : dto.getDayPlans()) {
                sb.append("\n第").append(dp.getDay()).append("天");
                if (dp.getDate() != null && !dp.getDate().isBlank()) {
                    sb.append("（").append(dp.getDate()).append("）");
                }
                sb.append("\n");
                if (dp.getSummary() != null && !dp.getSummary().isBlank()) {
                    sb.append(dp.getSummary()).append("\n");
                }
                if (dp.getAttractions() != null) {
                    for (ItineraryResponseDTO.AttractionVisit av : dp.getAttractions()) {
                        if (av.getName() == null) {
                            continue;
                        }
                        sb.append("- ").append(av.getName());
                        if (av.getTimeSlot() != null && !av.getTimeSlot().isBlank()) {
                            sb.append("（").append(av.getTimeSlot()).append("）");
                        }
                        if (av.getCost() != null) {
                            sb.append(" 费用：").append(av.getCost());
                        }
                        if (av.getNotes() != null && !av.getNotes().isBlank()) {
                            sb.append(" ").append(av.getNotes());
                        }
                        sb.append("\n");
                    }
                }
                if (dp.getTransportMode() != null && !dp.getTransportMode().isBlank()) {
                    sb.append("交通：").append(dp.getTransportMode()).append("\n");
                }
                if (dp.getHotelSuggestion() != null && !dp.getHotelSuggestion().isBlank()) {
                    sb.append("住宿：").append(dp.getHotelSuggestion()).append("\n");
                }
            }
        }
        if (dto.getEstimatedCost() != null) {
            sb.append("\n【预算】总费用：").append(dto.getEstimatedCost()).append("\n");
        }
        return sb.toString();
    }
}
