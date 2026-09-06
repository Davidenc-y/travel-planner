package com.travel.common.dto;

import com.travel.common.util.JsonUtils;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F23 D2 回归测试：@Data + @Builder 组合下 Lombok 不生成无参构造器，
 * 需补充 @NoArgsConstructor + @AllArgsConstructor 后才能被 Jackson 反序列化。
 */
class DtoJacksonTest {

    @Test
    void itineraryResponseDto_shouldDeserializeWithDayPlansAndMindmap() {
        String json = """
                {
                  "id": 1,
                  "title": "北京3日游",
                  "destination": "北京",
                  "days": 3,
                  "estimatedCost": 2365,
                  "dayPlans": [
                    {
                      "day": 1,
                      "date": "2026-08-01",
                      "summary": "故宫-天坛",
                      "attractions": [
                        {"name": "故宫博物院", "timeSlot": "09:00-12:00", "cost": 60, "notes": "提前预约"},
                        {"name": "天坛公园", "timeSlot": "14:00-16:00", "cost": 0, "notes": "仅购门票"}
                      ],
                      "transportMode": "地铁",
                      "hotelSuggestion": "王府井"
                    }
                  ],
                  "budgetBreakdown": {
                    "ticketCost": 120,
                    "mealCost": 1800,
                    "transportCost": 180,
                    "hotelCost": 750,
                    "otherCost": 285,
                    "totalCost": 3135,
                    "perPersonCost": 1045,
                    "currency": "CNY",
                    "notes": "按家庭3人、经济型住宿250元/晚计算"
                  },
                  "mindmap": {
                    "title": "旅行规划",
                    "destination": "北京",
                    "days": "3",
                    "budget": "5000",
                    "sections": [
                      {"title": "行程安排", "items": ["第1天"]}
                    ]
                  },
                  "generatedAt": "2026-08-12T10:00:00"
                }
                """;

        ItineraryResponseDTO dto = JsonUtils.fromJson(json, ItineraryResponseDTO.class);

        assertThat(dto).isNotNull();
        assertThat(dto.getId()).isEqualTo(1L);
        assertThat(dto.getDayPlans()).hasSize(1);
        assertThat(dto.getDayPlans().get(0).getAttractions()).hasSize(2);
        assertThat(dto.getDayPlans().get(0).getAttractions().get(0).getName()).isEqualTo("故宫博物院");
        assertThat(dto.getDayPlans().get(0).getAttractions().get(0).getCost()).isEqualByComparingTo("60");
        assertThat(dto.getDayPlans().get(0).getAttractions().get(1).getCost()).isEqualByComparingTo("0");
        assertThat(dto.getBudgetBreakdown()).isNotNull();
        assertThat(dto.getBudgetBreakdown().getTotalCost()).isEqualByComparingTo("3135");
        assertThat(dto.getBudgetBreakdown().getMealCost()).isEqualByComparingTo("1800");
        assertThat(dto.getBudgetBreakdown().getCurrency()).isEqualTo("CNY");
        assertThat(dto.getBudgetBreakdown().getNotes()).contains("经济型住宿");
        assertThat(dto.getMindmap()).isNotNull();
        assertThat(dto.getMindmap().getSections()).hasSize(1);
        assertThat(dto.getMindmap().getSections().get(0).getItems()).containsExactly("第1天");
    }

    @Test
    void attractionDto_shouldDeserialize() {
        AttractionDTO dto = JsonUtils.fromJson(
                "{\"id\":1,\"name\":\"故宫博物院\",\"city\":\"北京\",\"ticketPrice\":60,\"freeEntry\":false}",
                AttractionDTO.class);
        assertThat(dto).isNotNull();
        assertThat(dto.getName()).isEqualTo("故宫博物院");
        assertThat(dto.getTicketPrice()).isEqualByComparingTo("60");
        assertThat(dto.getFreeEntry()).isFalse();
    }

    @Test
    void chatResponseDto_shouldDeserialize() {
        ChatResponseDTO dto = JsonUtils.fromJson(
                "{\"sessionId\":\"s1\",\"response\":\"ok\",\"itineraryId\":9,\"tokens\":128}",
                ChatResponseDTO.class);
        assertThat(dto).isNotNull();
        assertThat(dto.getSessionId()).isEqualTo("s1");
        assertThat(dto.getItineraryId()).isEqualTo(9L);
    }
}
