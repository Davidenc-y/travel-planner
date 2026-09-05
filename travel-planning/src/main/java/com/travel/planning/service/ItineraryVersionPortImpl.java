package com.travel.planning.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.travel.common.entity.Itinerary;
import com.travel.common.dto.ItineraryResponseDTO;
import com.travel.common.enums.ItineraryStatus;
import com.travel.common.util.JsonUtils;
import com.travel.planning.agent.support.ItineraryVersionPort;
import com.travel.planning.repository.ItineraryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M13-2c：ItineraryVersionPort 的 planning 实现。
 *
 * <p>职责链：按 session_id 找最近行程 → REFINE 回写（合并 routePlan 后走
 * {@code ItineraryPersistenceService.updateCompleted}，自动触发版本 v+1 与 diff）；
 * 无关联行程 → 解析用户输入元数据（目的地/天数）创建一等行程资产。
 * 任一步失败仅 WARN 并返回 empty（观测不阻断聊天）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItineraryVersionPortImpl implements ItineraryVersionPort {

    private static final Pattern DAYS_DIGIT =
            Pattern.compile("(\\d{1,2})\\s*(?:日游|天)");
    private static final Pattern DAYS_CN =
            Pattern.compile("(?<![第])([一二三四五六七八九十]+)\\s*(?:日游|天)");
    private static final Pattern DEST_PLAN =
            Pattern.compile("(?:规划|安排|推荐|设计)(?:一下|一个|一次)?\\s*([\\u4e00-\\u9fa5]{2,10}?)"
                    + "\\s*(?:的)?(?:\\d{1,2}|[一二三四五六七八九十]+)\\s*(?:日游|天)");
    private static final Pattern DEST_GO =
            Pattern.compile("(?:去|到)([\\u4e00-\\u9fa5]{2,10}?)"
                    + "(?:玩|旅游|旅行|游玩|度假|，|,|\\s|\\d)");
    private static final Pattern DEST_LEADING =
            Pattern.compile("^([\\u4e00-\\u9fa5]{2,10}?)\\s*(?:的)?"
                    + "(?:\\d{1,2}|[一二三四五六七八九十]+)\\s*(?:日游|天)");
    private static final Pattern BUDGET_PATTERN =
            Pattern.compile("预算\\s*(?:约)?\\s*(\\d+(?:\\.\\d+)?)");

    private final ItineraryMapper itineraryMapper;
    private final ItineraryPersistenceService persistenceService;
    private final ItinerarySliceWriter sliceWriter;
    /** M15-3：聊天建行程/REFINE 时确定性生成思维导图（避免新版 mindmap 丢失） */
    private final MindmapGenerator mindmapGenerator;

    /** M11-1 版本服务（可选；缺失时首次创建不记快照，不影响主流程） */
    private ItineraryVersionService versionService;

    @Value("${travel.chat.supervisor.itinerary-writeback.create-on-chat:true}")
    private boolean createOnChat = true;

    @Autowired(required = false)
    void setItineraryVersionService(ItineraryVersionService versionService) {
        this.versionService = versionService;
    }

    @Override
    public Optional<Long> syncAfterPlanning(Long userId, String sessionId,
                                            String userInput, String routePlanJson,
                                            String budgetJson) {
        if (sessionId == null || sessionId.isBlank()
                || routePlanJson == null || routePlanJson.isBlank()
                || !validRoutePlan(routePlanJson)) {
            return Optional.empty();
        }
        try {
            Itinerary existing = findLatestBySession(sessionId);
            if (existing != null) {
                if (!userId.equals(existing.getUserId())) {
                    log.warn("[ItineraryWriteback] 会话行程归属不符，跳过回写: sessionId={}", sessionId);
                    return Optional.empty();
                }
                return refine(existing, sessionId, routePlanJson, budgetJson);
            }
            if (!createOnChat) {
                return Optional.empty();
            }
            return createFromChat(userId, sessionId, userInput, routePlanJson, budgetJson);
        } catch (Exception e) {
            log.warn("[ItineraryWriteback] 行程回写失败（静默降级）: sessionId={}, error={}",
                    sessionId, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Long> refine(Itinerary current, String sessionId, String routePlanJson,
                                  String budgetJson) {
        CostedContent merged = mergeContent(
                current.getContent(), routePlanJson, budgetJson);
        String mindmapData = buildMindmap(current, merged.content());
        int rows = persistenceService.updateCompleted(
                current.getId(),
                ItineraryStatus.GENERATED.name(),
                merged.content(),
                mindmapData,
                merged.estimatedCost());
        if (rows <= 0) {
            return Optional.empty();
        }
        sliceWriter.writeAfterGenerated(sessionId, current.getId(), merged.content());
        log.info("[ItineraryWriteback] REFINE 回写完成: itineraryId={}, sessionId={}",
                current.getId(), sessionId);
        return Optional.of(current.getId());
    }

    private Optional<Long> createFromChat(Long userId, String sessionId,
                                          String userInput, String routePlanJson,
                                          String budgetJson) {
        String destination = parseDestination(userInput);
        Integer days = parseDays(userInput);
        if (destination == null || days == null || days <= 0) {
            log.warn("[ItineraryWriteback] 无法从用户输入解析目的地/天数，跳过创建: sessionId={}",
                    sessionId);
            return Optional.empty();
        }
        Itinerary entity = new Itinerary();
        entity.setUserId(userId);
        entity.setDestination(destination);
        entity.setDays(days);
        entity.setBudget(parseBudget(userInput));
        entity.setStatus(ItineraryStatus.GENERATED.name());
        entity.setTitle(destination + days + "日游");
        CostedContent content = mergeContent(null, routePlanJson, budgetJson);
        entity.setContent(content.content());
        entity.setMindmapData(buildMindmap(entity, content.content()));
        entity.setEstimatedCost(content.estimatedCost());
        entity.setClientRequestId("chat-" + UUID.randomUUID());
        entity.setSessionId(sessionId);
        persistenceService.insert(entity);
        if (versionService != null) {
            versionService.recordFinalized(entity.getId(), entity.getContent(),
                    entity.getMindmapData(), entity.getEstimatedCost());
        }
        // 首次建版（v1）；版本服务缺失不影响主流程
        log.info("[ItineraryWriteback] 聊天规划已创建行程: itineraryId={}, destination={}, days={}",
                entity.getId(), destination, days);
        sliceWriter.writeAfterGenerated(sessionId, entity.getId(), entity.getContent());
        return Optional.of(entity.getId());
    }

    /** 用确定性 MindmapGenerator 根据最新 content 生成思维导图；失败保留原值/null。 */
    private String buildMindmap(Itinerary holder, String contentJson) {
        if (holder == null || contentJson == null || contentJson.isBlank()) {
            return null;
        }
        try {
            ItineraryResponseDTO.MindmapData mindmap = mindmapGenerator.generate(
                    holder.getTitle() == null || holder.getTitle().isBlank()
                            ? holder.getDestination() + holder.getDays() + "日游"
                            : holder.getTitle(),
                    holder.getDestination(),
                    holder.getDays(),
                    holder.getBudget() != null ? holder.getBudget().toPlainString() : null,
                    contentJson);
            return mindmap == null ? null : JsonUtils.toJson(mindmap);
        } catch (Exception e) {
            log.warn("[ItineraryWriteback] 思维导图生成失败（保留 null）: itineraryId={}, error={}",
                    holder.getId(), e.getMessage());
            return null;
        }
    }

    private Itinerary findLatestBySession(String sessionId) {
        return itineraryMapper.selectOne(new QueryWrapper<Itinerary>()
                .eq("session_id", sessionId)
                .orderByDesc("updated_at")
                .last("LIMIT 1"));
    }

    private static boolean validRoutePlan(String routePlanJson) {
        try {
            Map<?, ?> root = JsonUtils.fromJson(routePlanJson, Map.class);
            Object days = root == null ? null : root.get("days");
            return days instanceof List<?> list && !list.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** 终态 content + 估算费用（routePlan/budgetEstimate 合并）。 */
    private record CostedContent(String content, java.math.BigDecimal estimatedCost) {
    }

    static CostedContent mergeContent(String currentContent, String routePlanJson,
                                      String budgetJson) {
        Map<String, Object> root = new java.util.LinkedHashMap<>();
        if (currentContent != null && !currentContent.isBlank()) {
            try {
                Map<?, ?> parsed = JsonUtils.fromJson(currentContent, Map.class);
                if (parsed != null) {
                    parsed.forEach((k, v) -> root.put(String.valueOf(k), v));
                }
            } catch (Exception ignored) {
                // 旧 content 损坏时以空根重建（仅保留新 routePlan）
            }
        }
        try {
            Object routePlan = JsonUtils.fromJson(routePlanJson, Map.class);
            root.put("routePlan", routePlan);
            if (budgetJson != null && !budgetJson.isBlank()) {
                Object budget = JsonUtils.fromJson(budgetJson, Map.class);
                if (budget != null) {
                    root.put("budgetEstimate", budget);
                }
            }
            return new CostedContent(JsonUtils.toJson(root),
                    estimatedCostFrom(root.get("budgetEstimate")));
        } catch (Exception e) {
            String fallbackContent = currentContent == null || currentContent.isBlank()
                    ? "{\"routePlan\":" + routePlanJson + "}"
                    : currentContent;
            return new CostedContent(fallbackContent, null);
        }
    }

    @SuppressWarnings("unchecked")
    private static java.math.BigDecimal estimatedCostFrom(Object budgetEstimate) {
        if (!(budgetEstimate instanceof Map<?, ?> map)) {
            return null;
        }
        Object total = map.get("totalCost");
        if (total == null) {
            return null;
        }
        try {
            return new java.math.BigDecimal(total.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Integer parseDays(String input) {
        String question = currentQuestion(input);
        if (question == null) {
            return null;
        }
        Matcher m = DAYS_DIGIT.matcher(question);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        Matcher cn = DAYS_CN.matcher(question);
        if (cn.find()) {
            return chineseNumber(cn.group(1));
        }
        return null;
    }

    static String parseDestination(String input) {
        String question = currentQuestion(input);
        if (question == null || question.isBlank()) {
            return null;
        }
        Matcher plan = DEST_PLAN.matcher(question);
        if (plan.find()) {
            return normalizeCity(plan.group(1));
        }
        Matcher go = DEST_GO.matcher(question);
        if (go.find()) {
            return normalizeCity(go.group(1));
        }
        Matcher leading = DEST_LEADING.matcher(question.trim());
        if (leading.find()) {
            return normalizeCity(leading.group(1));
        }
        return null;
    }

    static BigDecimal parseBudget(String input) {
        String question = currentQuestion(input);
        if (question == null) {
            return null;
        }
        Matcher m = BUDGET_PATTERN.matcher(question);
        if (m.find()) {
            try {
                return new BigDecimal(m.group(1));
            } catch (NumberFormatException ignored) {
                // 非法数字忽略
            }
        }
        return null;
    }

    /** 从完整 composed 上下文中截取“【当前问题】”之后的用户原始问题。 */
    private static String currentQuestion(String input) {
        if (input == null) {
            return null;
        }
        String marker = "【当前问题】";
        int idx = input.lastIndexOf(marker);
        String question = idx >= 0 ? input.substring(idx + marker.length()) : input;
        int userId = question.lastIndexOf(", userId=");
        if (userId >= 0) {
            question = question.substring(0, userId);
        }
        return question.trim();
    }

    private static String normalizeCity(String city) {
        String c = city == null ? "" : city.trim();
        if (c.endsWith("市") && c.length() > 2) {
            c = c.substring(0, c.length() - 1);
        }
        if (c.isEmpty() || c.length() < 2 || c.length() > 4) {
            return null;
        }
        if (c.contains("避开人流") || c.contains("宽窄巷子") || c.contains("第一天")
                || c.contains("第二天") || c.contains("改到") || c.contains("游玩")
                || c.contains("把") || c.contains("第") || c.contains("到")
                || c.contains("改") || c.contains("的")) {
            return null;
        }
        return c;
    }

    private static Integer chineseNumber(String cn) {
        char[] chars = cn.toCharArray();
        int sum = 0;
        for (char ch : chars) {
            int v = switch (ch) {
                case '一' -> 1;
                case '二' -> 2;
                case '三' -> 3;
                case '四' -> 4;
                case '五' -> 5;
                case '六' -> 6;
                case '七' -> 7;
                case '八' -> 8;
                case '九' -> 9;
                case '十' -> 10;
                default -> -1;
            };
            if (v < 0) {
                return null;
            }
            sum += v;
        }
        return sum;
    }
}
