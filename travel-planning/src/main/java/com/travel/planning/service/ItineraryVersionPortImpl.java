package com.travel.planning.service;

import com.travel.planning.prompt.Markers;
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

    private final ItineraryMapper itineraryMapper;
    private final ItineraryPersistenceService persistenceService;
    private final ItinerarySliceWriter sliceWriter;
    /** M15-3：聊天建行程/REFINE 时确定性生成思维导图（避免新版 mindmap 丢失） */
    private final MindmapGenerator mindmapGenerator;
    /** M28-3：routePlan 首日日期提取（start_date 约束列） */
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** M18-2：解析词表/算法迁 ItineraryWritebackProperties（默认值=原字面量，可 yml 覆盖） */
    private final ItineraryWritebackProperties parseProps;
    /** M23（P-D/E2）：单锚定时 REFINE 目标=锚定行程。 */
    private final com.travel.planning.memory.anchor.SessionAnchorStore sessionAnchorStore;
    /** M20-1：聊天建行程/REFINE 成功后异步重算行为画像（此前仅 /generate 与 /resume 触发） */
    private final com.travel.planning.memory.longterm.behavior.BehaviorProfileService behaviorProfileService;


    /** M11-1 版本服务（可选；缺失时首次创建不记快照，不影响主流程） */
    private ItineraryVersionService versionService;

    @Value("${travel.chat.supervisor.itinerary-writeback.create-on-chat:true}")
    private boolean createOnChat = true;

    @Autowired(required = false)
    void setItineraryVersionService(ItineraryVersionService versionService) {
        this.versionService = versionService;
    }

    /**
     * M20-1：成功建行程/建版后异步触发行为画像重算（chat 路径补挂，此前仅 /generate 与
     * /resume 触发；重算无 LLM、有界查询 + 单飞，失败静默）。
     */
    private void triggerBehaviorRecompute(Long userId) {
        if (userId == null || userId <= 0) {
            return;
        }
        java.util.concurrent.CompletableFuture.runAsync(
                        () -> behaviorProfileService.recomputeIfEnabled(userId))
                .whenComplete((v, e) -> {
                    if (e != null) {
                        log.warn("[BehaviorProfile] 聊天回写后重算触发失败（不影响主流程）: userId={}, error={}",
                                userId, e.getMessage());
                    }
                });
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
            // M28-11：解析输入纯净化——writeback 的确定性解析（party/budget/days/目的地）
            // 必须基于"用户显式输入"（【本轮偏好约束】段 + 【当前问题】尾段），不得基于
            // 含画像/行为特征/历史对话的完整组装文本。实证（2026-09-08 09:36）：
            // 画像"常见同行：家庭"混入 parseParty → 未提同行人的新会话凭空写入
            // party=家庭；REFINE"同行人改成情侣"时"家庭"词又抢先命中 → 列不更新，
            // preferenceSync 回写前端偏好标签恒为"家庭"（与聊天回答口径分裂）。
            String explicitInput = extractExplicitInput(userInput);
            Itinerary existing = findLatestBySession(sessionId,
                    sessionAnchorStore.getAnchors(sessionId));
            if (existing != null) {
                if (!userId.equals(existing.getUserId())) {
                    log.warn("[ItineraryWriteback] 会话行程归属不符，跳过回写: sessionId={}", sessionId);
                    return Optional.empty();
                }
                Optional<Long> refined = refine(existing, sessionId, explicitInput, routePlanJson, budgetJson);
                refined.ifPresent(id -> triggerBehaviorRecompute(userId));
                return refined;
            }
            if (!createOnChat) {
                return Optional.empty();
            }
            Optional<Long> created = createFromChat(userId, sessionId, explicitInput, routePlanJson, budgetJson);
            created.ifPresent(id -> triggerBehaviorRecompute(userId));
            return created;
        } catch (Exception e) {
            log.warn("[ItineraryWriteback] 行程回写失败（静默降级）: sessionId={}, error={}",
                    sessionId, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Long> refine(Itinerary current, String sessionId, String userInput,
                                  String routePlanJson, String budgetJson) {
        CostedContent merged = mergeContent(
                current.getContent(), routePlanJson, budgetJson);
        String mindmapData = buildMindmap(current, merged.content());
        // M28-7：本轮有效约束先解析（未提及=null）——vN 版本快照必须记录"本轮后"的约束值，
        // 而版本记录发生在 applyRefinedConstraints 之前，故显式传参而非回退读列
        java.math.BigDecimal refineBudget = parseBudget(userInput);
        Integer refineDays = parseDays(userInput);
        String refineStart = extractStartDate(routePlanJson);
        // M28-9：同行人确定性解析（"同行改成情侣"→情侣；未提及 null 不覆盖）
        String refineParty = parseParty(userInput);
        java.math.BigDecimal snapBudget = refineBudget != null ? refineBudget : current.getBudget();
        Integer snapDays = refineDays != null && refineDays > 0 ? refineDays : current.getDays();
        String snapStart = refineStart != null ? refineStart : current.getStartDate();
        int rows = persistenceService.updateCompleted(
                current.getId(),
                ItineraryStatus.GENERATED.name(),
                merged.content(),
                mindmapData,
                merged.estimatedCost(),
                snapDays, snapBudget, snapStart);
        if (rows <= 0) {
            return Optional.empty();
        }
        // M26-F1：用本轮确定性解析更新约束列（预算改成 5000 → t_itinerary.budget=5000；
        // 天数变更同理；未提及的字段保持原值）——行程详情页预算展示与偏好回写的数据源
        // M28-3：出发日期改从 routePlan 首日提取（"从9月20日开始"→LLM 输出首日 2026-09-20）
        // M28-12：兴趣确定性解析（"多安排美食和亲子项目"→["美食","亲子"]；未提及 null 不覆盖）
        applyRefinedConstraints(current, refineBudget, refineDays, refineStart, refineParty,
                parseInterests(userInput));
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
        // M28-12：首版建程解析兴趣（JSON 数组文本，与 /plan 表单路径同格式）
        List<String> interests = parseInterests(userInput);
        if (interests != null) {
            entity.setInterests(JsonUtils.toJson(interests));
        }
        // M28-9：同行人同确定性解析（"和家人去成都"→家庭；未提及 null）
        entity.setParty(parseParty(userInput));
        // M28-3：出发日期取 routePlan 首日（与 /plan 表单路径对齐，start_date 列不再恒空）
        entity.setStartDate(extractStartDate(routePlanJson));
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
            // M28-7：首版带约束快照（切换回 v1 时 budget/days/startDate 可恢复）
            versionService.recordFinalized(entity.getId(), entity.getContent(),
                    entity.getMindmapData(), entity.getEstimatedCost(),
                    entity.getDays(), entity.getBudget(), entity.getStartDate());
        }
        // 首次建版（v1）；版本服务缺失不影响主流程
        log.info("[ItineraryWriteback] 聊天规划已创建行程: itineraryId={}, destination={}, days={}",
                entity.getId(), destination, days);
        sliceWriter.writeAfterGenerated(sessionId, entity.getId(), entity.getContent());
        return Optional.of(entity.getId());
    }

    /**
     * M26-F1：REFINE 后同步约束列（确定性 parse；null=未提及不覆盖）。
     * 直接 UpdateWrapper 定向更新，避免与 updateCompleted 的全量更新耦合。
     * M28-3：出发日期来源 routePlan 首日日期（权威=LLM 本轮实际输出）。
     */
    private void applyRefinedConstraints(Itinerary current, String userInput, String routePlanJson) {
        applyRefinedConstraints(current, parseBudget(userInput), parseDays(userInput),
                extractStartDate(routePlanJson), parseParty(userInput),
                parseInterests(userInput));
    }

    /** M28-7：约束值由调用方解析传入（refine 复用同一份解析结果）。 */
    private void applyRefinedConstraints(Itinerary current,
            java.math.BigDecimal newBudget, Integer newDays, String newStart, String newParty) {
        applyRefinedConstraints(current, newBudget, newDays, newStart, newParty, null);
    }

    /** M28-12：增加 interests 约束列（与 budget/days/start/party 同款定向更新）。 */
    private void applyRefinedConstraints(Itinerary current,
            java.math.BigDecimal newBudget, Integer newDays, String newStart, String newParty,
            List<String> newInterests) {
        try {
            boolean partyChanged = newParty != null && !newParty.isBlank()
                    && !newParty.equals(current.getParty());
            boolean budgetChanged = newBudget != null
                    && (current.getBudget() == null || newBudget.compareTo(current.getBudget()) != 0);
            boolean daysChanged = newDays != null && newDays > 0
                    && !newDays.equals(current.getDays());
            boolean startChanged = newStart != null && !newStart.equals(current.getStartDate());
            String newInterestsJson = newInterests == null || newInterests.isEmpty()
                    ? null : JsonUtils.toJson(newInterests);
            boolean interestsChanged = newInterestsJson != null
                    && !newInterestsJson.equals(current.getInterests());
            if (!budgetChanged && !daysChanged && !startChanged && !partyChanged && !interestsChanged) {
                return;
            }
            com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Itinerary> uw =
                    new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Itinerary>()
                            .eq("id", current.getId());
            if (budgetChanged) {
                uw.set("budget", newBudget);
                current.setBudget(newBudget);
            }
            if (daysChanged) {
                uw.set("days", newDays);
                current.setDays(newDays);
            }
            if (startChanged) {
                uw.set("start_date", newStart);
                current.setStartDate(newStart);
            }
            if (partyChanged) {
                uw.set("party", newParty);
                current.setParty(newParty);
            }
            if (interestsChanged) {
                uw.set("interests", newInterestsJson);
                current.setInterests(newInterestsJson);
            }
            itineraryMapper.update(null, uw);
            log.info("[ItineraryWriteback] 约束列已更新: itineraryId={}, budget={}, days={}, startDate={}, party={}, interests={}",
                    current.getId(),
                    budgetChanged ? newBudget.toPlainString() : "(unchanged)",
                    daysChanged ? newDays : "(unchanged)",
                    startChanged ? newStart : "(unchanged)",
                    partyChanged ? newParty : "(unchanged)",
                    interestsChanged ? newInterestsJson : "(unchanged)");
        } catch (Exception e) {
            log.warn("[ItineraryWriteback] 约束列更新失败（不阻断回写）: id={}, error={}",
                    current.getId(), e.getMessage());
        }
    }

    /**
     * M28-11：从 composed 组装文本提取"用户显式输入"（供确定性解析）。
     *
     * <p>取两段：①【本轮偏好约束】段（用户显式标签，如"- 同行人:家庭"——显式
     * 设置应被行程列记录）；②【当前问题】尾段（用户原始消息，右边界为
     * 【当前日期】行——与 PlanningHeuristics 同一契约）。两段均无标记时
     * 回退全文（保守，兼容非组装调用方如单测直传短文本）。</p>
     */
    static String extractExplicitInput(String composed) {
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
    static String parseParty(String input) {
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
    private static final Map<String, String> PARTY_CANONICAL_WORDS = java.util.Map.ofEntries(
            java.util.Map.entry("带小孩", "家庭"), java.util.Map.entry("带娃", "家庭"),
            java.util.Map.entry("亲子", "家庭"), java.util.Map.entry("家庭", "家庭"),
            java.util.Map.entry("家人", "家庭"),
            java.util.Map.entry("情侣", "情侣"), java.util.Map.entry("夫妻", "情侣"),
            java.util.Map.entry("两个人", "情侣"),
            java.util.Map.entry("朋友", "朋友"), java.util.Map.entry("闺蜜", "朋友"),
            java.util.Map.entry("同事", "朋友"),
            java.util.Map.entry("独行", "独行"), java.util.Map.entry("一个人", "独行"));

    /**
     * M28-12：从用户显式输入解析兴趣（词表与前端 INTEREST_OPTIONS 同款：文化/自然/
     * 美食/购物/亲子/休闲；"历史/爬山"等画像词不在此列，不臆造映射）。未提及返回 null。
     */
    static List<String> parseInterests(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        List<String> hits = new java.util.ArrayList<>();
        for (String interest : new String[]{"文化", "自然", "美食", "购物", "亲子", "休闲"}) {
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
    private String extractStartDate(String routePlanJson) {
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
        return findLatestBySession(sessionId, null);
    }

    /**
     * M23（P-D/E2）：单锚定时 REFINE 目标=锚定行程（确定性，替代隐式"最近更新"）；
     * 锚定行程仍要求归属本人（selectById 校验在调用方 syncAfterPlanning 语义内）。
     * 多锚定/无锚定回退原"最近更新"语义。
     */
    private Itinerary findLatestBySession(String sessionId, List<Long> anchorIds) {
        if (anchorIds != null && anchorIds.size() == 1) {
            Itinerary anchored = itineraryMapper.selectById(anchorIds.get(0));
            if (anchored != null) {
                return anchored;
            }
        }
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

    Integer parseDays(String input) {
        return parseProps.parseDays(input);
    }

    String parseDestination(String input) {
        return parseProps.parseDestination(input);
    }

    BigDecimal parseBudget(String input) {
        return parseProps.parseBudget(input);
    }

}
