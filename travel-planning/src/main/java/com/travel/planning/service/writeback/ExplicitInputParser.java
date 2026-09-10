package com.travel.planning.service.writeback;

import com.travel.common.dto.PreferenceVocabulary;
import com.travel.planning.prompt.Markers;
import com.travel.planning.service.ItineraryWritebackProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.math.BigDecimal;

/**
 * 回写确定性解析器：自 ItineraryVersionPortImpl 原样迁出（重构 R1.1），行为与文案零变更。
 *
 * <p>无状态纯解析（词表/正则单源仍为 {@link ItineraryWritebackProperties}，M18-2），
 * 供 ItineraryVersionPortImpl 构造器注入调用。</p>
 */
@Slf4j
@Component
public class ExplicitInputParser {

    /** M28-3：routePlan 首日日期提取（start_date 约束列） */
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** M18-2：解析词表/算法迁 ItineraryWritebackProperties（默认值=原字面量，可 yml 覆盖） */
    private final ItineraryWritebackProperties parseProps;

    public ExplicitInputParser(ItineraryWritebackProperties parseProps) {
        this.parseProps = parseProps;
    }

    /**
     * M28-11：从 composed 组装文本提取"用户显式输入"（供确定性解析）。
     *
     * <p>取两段：①【本轮偏好约束】段（用户显式标签，如"- 同行人:家庭"——显式
     * 设置应被行程列记录）；②【当前问题】尾段（用户原始消息，右边界为
     * 【当前日期】行——与 PlanningHeuristics 同一契约）。两段均无标记时
     * 回退全文（保守，兼容非组装调用方如单测直传短文本）。</p>
     */
    public static String extractExplicitInput(String composed) {
        if (composed == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int prefIdx = composed.indexOf(Markers.PREFERENCE_TAGS);
        if (prefIdx >= 0) {
            sb.append(composed, prefIdx, nextMarkerEnd(composed, prefIdx)).append('\n');
        }
        int qIdx = composed.lastIndexOf(Markers.CURRENT_QUESTION);
        if (qIdx >= 0) {
            String tail = composed.substring(qIdx + Markers.CURRENT_QUESTION.length());
            int dateIdx = tail.indexOf(Markers.CURRENT_DATE);
            if (dateIdx >= 0) {
                tail = tail.substring(0, dateIdx);
            }
            sb.append(tail.trim());
        }
        String extracted = sb.toString().trim();
        return extracted.isEmpty() ? composed : extracted;
    }

    /** 偏好约束段的右边界：下一个行首"【"标记或文本末尾。 */
    private static int nextMarkerEnd(String composed, int from) {
        int idx = composed.indexOf("\n【", from);
        return idx < 0 ? composed.length() : idx;
    }

    /**
     * M28-9：从用户输入解析同行人（与 chat-domain party-patterns 同语义词表，
     * 本地独立实现避免 planning→chat-domain 反向依赖）。未提及返回 null（不臆造）。
     * M28-11：多词命中取<b>位置最靠后</b>者=最新口径（同 budgetNumberOf 思路，
     * "之前是家庭，现在改成情侣"→情侣；旧实现按词表顺序家庭恒先命中）。
     */
    public static String parseParty(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String best = null;
        int bestEnd = -1;
        for (Map.Entry<String, String> e : PARTY_CANONICAL_WORDS.entrySet()) {
            int idx = input.indexOf(e.getKey());
            if (idx >= 0) {
                int end = idx + e.getKey().length();
                if (end > bestEnd) {
                    bestEnd = end;
                    best = e.getValue();
                }
            }
        }
        return best;
    }

    /** M28-11：party 词→规范值（与 SessionFactConsolidator.PARTY_CANONICAL 同口径）。 */
    private static final Map<String, String> PARTY_CANONICAL_WORDS = PreferenceVocabulary.PARTY_CANONICAL;

    /**
     * M28-12：从用户显式输入解析兴趣（词表与前端 INTEREST_OPTIONS 同款：文化/自然/
     * 美食/购物/亲子/休闲；"历史/爬山"等画像词不在此列，不臆造映射）。未提及返回 null。
     */
    public static List<String> parseInterests(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        List<String> hits = new java.util.ArrayList<>();
        for (String interest : PreferenceVocabulary.INTEREST_WORDS) {
            if (input.contains(interest) && !hits.contains(interest)) {
                hits.add(interest);
            }
        }
        return hits.isEmpty() ? null : hits;
    }

    /**
     * M28-3：从 routePlan JSON 提取首日出发日期（yyyy-MM-dd）。
     * 兼容根级 days 与 routePlan.days 两种形态；日期非法/缺失返回 null（不覆盖）。
     */
    public String extractStartDate(String routePlanJson) {
        if (routePlanJson == null || routePlanJson.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(routePlanJson);
            com.fasterxml.jackson.databind.JsonNode days = root.path("days");
            if (!days.isArray() || days.isEmpty()) {
                days = root.path("routePlan").path("days");
            }
            if (days.isArray() && !days.isEmpty()) {
                String date = days.get(0).path("date").asText(null);
                if (date != null && date.length() >= 10) {
                    return java.time.LocalDate.parse(date.trim().substring(0, 10)).toString();
                }
            }
        } catch (Exception e) {
            log.debug("[ItineraryWriteback] routePlan 首日日期解析降级: {}", e.getMessage());
        }
        return null;
    }

    public Integer parseDays(String input) {
        return parseProps.parseDays(input);
    }

    public String parseDestination(String input) {
        return parseProps.parseDestination(input);
    }

    public BigDecimal parseBudget(String input) {
        return parseProps.parseBudget(input);
    }

}
