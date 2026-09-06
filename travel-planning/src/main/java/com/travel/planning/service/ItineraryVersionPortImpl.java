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
            Itinerary existing = findLatestBySession(sessionId,
                    sessionAnchorStore.getAnchors(sessionId));
            if (existing != null) {
                if (!userId.equals(existing.getUserId())) {
                    log.warn("[ItineraryWriteback] 会话行程归属不符，跳过回写: sessionId={}", sessionId);
                    return Optional.empty();
                }
                Optional<Long> refined = refine(existing, sessionId, routePlanJson, budgetJson);
                refined.ifPresent(id -> triggerBehaviorRecompute(userId));
                return refined;
            }
            if (!createOnChat) {
                return Optional.empty();
            }
            Optional<Long> created = createFromChat(userId, sessionId, userInput, routePlanJson, budgetJson);
            created.ifPresent(id -> triggerBehaviorRecompute(userId));
            return created;
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
