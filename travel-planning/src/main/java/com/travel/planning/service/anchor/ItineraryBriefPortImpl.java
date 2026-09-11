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
import org.springframework.beans.factory.annotation.Autowired;
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

    /** B2.1：brief 快照旁路写入器（required=false——测试直构/无 Redis 场景为 null 静默跳过；字段注入保持 @RequiredArgsConstructor 生成构造器签名不变，R4.1 先例） */
    @Autowired(required = false)
    private BriefRedisSnapshotWriter snapshotWriter;

    @Override
    public Optional<ItineraryBrief> briefOf(Long userId, Long itineraryId) {
        if (itineraryId == null) {
            return Optional.empty();
        }
        Itinerary entity = itineraryMapper.selectById(itineraryId);
        if (entity == null || !userId.equals(entity.getUserId())) {
            return Optional.empty(); // 不存在或非本人：锚定自愈剔除
        }
        ItineraryBrief brief = new ItineraryBrief(
                entity.getId(),
                entity.getTitle(),
                entity.getDestination(),
                entity.getDays(),
                entity.getStartDate(),
                entity.getBudget() == null ? null : entity.getBudget().toPlainString(),
                entity.getParty(),
                parseInterestsColumn(entity.getInterests()),
                entity.getVersion(),
                extractAttractionNames(entity.getContent()));
        if (snapshotWriter != null) {
            // B2.1：组装成功后旁路写 Redis 快照（writer 内部 try-catch，失败仅 log.warn 不影响主流程）
            snapshotWriter.writeSnapshot(entity.getSessionId(), brief);
        }
        return Optional.of(brief);
    }

    /** M28-12：interests 列（JSON 数组文本，如 ["文化","自然"]）容错解析为列表。 */
    private List<String> parseInterestsColumn(String interestsJson) {
        if (interestsJson == null || interestsJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode arr = objectMapper.readTree(interestsJson);
            if (arr.isArray()) {
                List<String> result = new ArrayList<>();
                arr.forEach(n -> {
                    String t = n.asText(null);
                    if (t != null && !t.isBlank()) {
                        result.add(t);
                    }
                });
                return result;
            }
        } catch (Exception e) {
            // 旧格式/损坏内容降级为空列表（brief 其余字段照常）
        }
        return List.of();
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
