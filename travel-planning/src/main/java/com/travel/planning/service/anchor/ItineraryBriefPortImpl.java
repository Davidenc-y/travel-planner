package com.travel.planning.service.anchor;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.common.entity.Itinerary;
import com.travel.planning.memory.anchor.ItineraryBrief;
import com.travel.planning.memory.anchor.ItineraryBriefPort;
import com.travel.planning.repository.ItineraryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * M23（E1）：{@link ItineraryBriefPort} planning 实现——
 * 从 t_itinerary 装配 brief（归属校验 + content JSON 容错解析景点名）。
 *
 * <p>briefOf 双重校验（存在 + 归属）与 ItineraryService.getById(66,9) 同语义；
 * content 解析失败降级为"无景点名单"（brief 其余字段照常注入，不抛错）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItineraryBriefPortImpl implements ItineraryBriefPort {

    private final ItineraryMapper itineraryMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Optional<ItineraryBrief> briefOf(Long userId, Long itineraryId) {
        if (itineraryId == null) {
            return Optional.empty();
        }
        Itinerary entity = itineraryMapper.selectById(itineraryId);
        if (entity == null || !userId.equals(entity.getUserId())) {
            return Optional.empty(); // 不存在或非本人：锚定自愈剔除
        }
        return Optional.of(new ItineraryBrief(
                entity.getId(),
                entity.getTitle(),
                entity.getDestination(),
                entity.getDays(),
                entity.getStartDate(),
                entity.getBudget() == null ? null : entity.getBudget().toPlainString(),
                entity.getParty(),
                entity.getVersion(),
                extractAttractionNames(entity.getContent())));
    }

    @Override
    public List<Long> findSessionItineraryIds(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        return itineraryMapper.selectList(new QueryWrapper<Itinerary>()
                        .eq("session_id", sessionId)
                        .orderByDesc("updated_at")
                        .select("id"))
                .stream().map(Itinerary::getId).toList();
    }

    /** content JSON 容错解析每日景点名（顺序遍历 dayPlans[].attractions[].name）。 */
    private List<String> extractAttractionNames(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(content);
            JsonNode dayPlans = root.path("dayPlans");
            List<String> names = new ArrayList<>();
            if (dayPlans.isArray()) {
                for (JsonNode day : dayPlans) {
                    for (JsonNode attr : day.path("attractions")) {
                        String name = attr.path("name").asText(null);
                        if (name != null && !name.isBlank()) {
                            names.add(name);
                        }
                    }
                }
            }
            return names;
        } catch (Exception e) {
            log.debug("[ItineraryBriefPort] content 解析降级（无景点名单）: {}", e.getMessage());
            return List.of();
        }
    }
}
