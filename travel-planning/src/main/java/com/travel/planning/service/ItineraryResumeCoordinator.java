package com.travel.planning.service;

import com.travel.common.dto.ItineraryGenerateRequestDTO;
import com.travel.common.dto.ItineraryResponseDTO;
import com.travel.common.entity.Itinerary;
import com.travel.common.entity.TravelProfile;
import com.travel.common.enums.ItineraryStatus;
import com.travel.common.exception.BusinessException;
import com.travel.common.exception.ItineraryGenerationException;
import com.travel.common.util.JsonUtils;
import com.travel.planning.memory.longterm.ProfileContextAssembler;
import com.travel.planning.memory.longterm.ProfilePort;
import com.travel.planning.prompt.PromptTemplates;
import com.travel.planning.repository.ItineraryMapper;
import com.travel.planning.workflow.ItineraryTaskSnapshotPort;
import com.travel.planning.workflow.ItineraryStateMachineProperties;
import com.travel.planning.workflow.TravelWorkflowBuilder;
import com.travel.planning.weather.WeatherContextBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M10-1c：行程断点续跑语义协调器（自 ItineraryService 拆出）。
 *
 * <p>职责：断点节点解析、快照污染过滤、resume 消息/请求重建、快照注入与
 * 僵尸 GENERATING 判定。全部为确定性纯逻辑（零 LLM）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItineraryResumeCoordinator {

    private static final long MINDMAP_TIMEOUT_SECONDS = 60;

    private final ItineraryStateMachineProperties stateMachineProps;
    private final ItineraryMapper itineraryMapper;
    private final TravelWorkflowBuilder workflowBuilder;
    private final ProfilePort profilePort;
    private final MindmapGenerator mindmapGenerator;
    private final ProfileContextAssembler profileContextAssembler;
    private final PromptTemplates promptTemplates;
    private final ItineraryPersistenceService persistenceService;
    private final ItineraryTaskSnapshotPort snapshotPort;
    private final ItineraryDtoAssembler dtoAssembler;
    private final ItineraryGraphExecutor graphExecutor;
    private final WeatherContextBuilder weatherContextBuilder;

    /** M4-9/P1-5：显式断点续跑入口（守卫 + 归属校验）。 */
    public ItineraryResponseDTO resume(Long id, Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(40101, "用户未登录");
        }
        Itinerary task = itineraryMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(40401, "行程不存在: " + id);
        }
        if (!userId.equals(task.getUserId())) {
            throw new BusinessException(40302, "无权访问该行程");
        }
        String status = String.valueOf(task.getStatus());
        boolean resumable = "FAILED".equals(status)
                || ("GENERATING".equals(status) && isZombie(task));
        if (!resumable) {
            if ("GENERATING".equals(status)) {
                throw new BusinessException(40905, "行程正在生成中，请稍后重试");
            }
            throw new BusinessException(40903, "行程当前状态不支持继续生成: " + status);
        }
        return resumeExisting(task);
    }

    /** 幂等命中/显式 resume 共用：快照断点判定 + 子图续跑 + 终态更新。 */
    public ItineraryResponseDTO resumeExisting(Itinerary task) {
        Map<String, String> snapshots = sanitizeSnapshots(snapshotPort.loadLatestByTask(task.getId()));
        String resumeFrom = resolveResumeFrom(snapshots);
        log.info("行程断点续跑: taskId={}, resumeFrom={}, 快照节点={}",
                task.getId(), resumeFrom, snapshots.keySet());

        ItineraryGenerateRequestDTO req = rebuildRequest(task);
        TravelProfile profile = profilePort.getOrCreate(task.getUserId());
        String profileContext = profileContextAssembler.assemble(profile);
        String userInput = promptTemplates.itineraryUserInput().formatted(
                req.getDestination(), req.getDays(),
                req.getBudget() != null ? req.getBudget() + "元" : "不限",
                req.getInterests() != null ? req.getInterests() : "不限",
                req.getParty() != null ? req.getParty() : "不限",
                req.getStartDate() != null ? req.getStartDate() : "未指定");
        if (!profileContext.isBlank()) {
            userInput = profileContext + "\n\n" + userInput;
        }
        // M15-1：续跑同样注入天气（不改变断点语义；关闭时为空串）
        String weatherContext = weatherContextBuilder.build(
                task.getDestination(), task.getStartDate(), req.getDays());
        if (weatherContext != null && !weatherContext.isBlank()) {
            userInput = weatherContext + "\n\n" + userInput;
        }
        Map<String, Object> initialState = new HashMap<>();
        initialState.put("userInput", userInput);
        initialState.put("userId", task.getUserId());
        initialState.put("retryCount", 0);
        initialState.put("retrievalQuery", buildRetrievalQuery(req));
        initialState.put(com.travel.planning.workflow.SnapshotNodeWrapper.TASK_ID_KEY, task.getId());
        // M14-1b：消费水平/出行人数确定性下传（resume 子图同样生效）
        if (profile != null && profile.getConsumeLevel() != null) {
            initialState.put("consumeLevel", profile.getConsumeLevel());
        }
        if (req.getParty() != null && !req.getParty().isBlank()) {
            initialState.put("party", req.getParty());
        }
        injectSnapshot(initialState, "preference_analysis", "preference", snapshots);
        injectSnapshot(initialState, "attraction_filter", "attractions", snapshots);
        injectSnapshot(initialState, "route_arrangement", "routePlan", snapshots);
        injectSnapshot(initialState, "budget_estimation", "budgetEstimate", snapshots);
        initialState.put("messages", buildResumeMessage(userInput, snapshots));

        long start = System.currentTimeMillis();
        try {
            var finalState = graphExecutor.execute(
                    workflowBuilder.buildWorkflow(resumeFrom), initialState);
            String itineraryJson = finalState.value("itinerary", "").toString();
            String mindmapJson = finalState.value("mindmap", "").toString();
            BigDecimal estimatedCost = dtoAssembler.estimatedCostFrom(itineraryJson);
            String finalMindmap = mindmapJson;
            if (finalMindmap == null || finalMindmap.isBlank()) {
                finalMindmap = graphExecutor.withTimeout(() -> {
                    ItineraryResponseDTO.MindmapData mindmap = mindmapGenerator.generate(
                            task.getDestination() + task.getDays() + "日游",
                            task.getDestination(), task.getDays(),
                            task.getBudget() != null ? task.getBudget().toString() : null,
                            itineraryJson);
                    return JsonUtils.toJson(mindmap);
                }, MINDMAP_TIMEOUT_SECONDS, "思维导图生成");
            }
            persistenceService.updateCompleted(task.getId(), ItineraryStatus.GENERATED.name(),
                    itineraryJson, finalMindmap, estimatedCost);
            task.setStatus(ItineraryStatus.GENERATED.name());
            task.setContent(itineraryJson);
            task.setMindmapData(finalMindmap);
            task.setEstimatedCost(estimatedCost);
            log.info("行程续跑成功: taskId={}, resumeFrom={}, 耗时={}ms",
                    task.getId(), resumeFrom, System.currentTimeMillis() - start);
            try {
                ItineraryGenerateRequestDTO rebuilt = rebuildRequest(task);
                profilePort.recordTrip(task.getUserId(), task.getDestination(),
                        JsonUtils.toJson(rebuilt.getInterests()), task.getTitle(),
                        task.getBudget(), task.getParty());
            } catch (Exception pe) {
                log.warn("续跑后画像更新失败（不影响主流程）: {}", pe.getMessage());
            }
            return dtoAssembler.toResponseDTO(task, isResumable(task));
        } catch (Exception e) {
            try {
                persistenceService.updateStatus(task.getId(), ItineraryStatus.FAILED.name());
            } catch (Exception markEx) {
                log.warn("续跑失败状态标记异常: taskId={}, {}", task.getId(), markEx.getMessage());
            }
            if (e instanceof ItineraryGenerationException ige) {
                throw ige;
            }
            log.error("行程续跑失败: taskId={}, resumeFrom={}", task.getId(), resumeFrom, e);
            throw new ItineraryGenerationException(
                    ItineraryGenerationOrchestrator.buildUpstreamMessage(e), e);
        }
    }

    private static String buildRetrievalQuery(ItineraryGenerateRequestDTO req) {
        StringBuilder sb = new StringBuilder("目的地：").append(req.getDestination());
        if (req.getInterests() != null && !req.getInterests().isEmpty()) {
            sb.append("\n兴趣：").append(String.join("、", req.getInterests()));
        }
        return sb.toString();
    }

    /** 权威可续标志（与 resume 端点守卫同口径）。 */
    public boolean isResumable(Itinerary entity) {
        if (entity == null || entity.getStatus() == null) {
            return false;
        }
        String status = entity.getStatus();
        return "FAILED".equals(status)
                || ("GENERATING".equals(status) && isZombie(entity));
    }

    /** 断点=最新完成节点的下一个执行单元（无快照→整图重跑） */
    public static String resolveResumeFrom(Map<String, String> snapshots) {
        if (snapshots.containsKey("budget_estimation")) {
            return "itinerary_optimize";
        }
        if (snapshots.containsKey("route_arrangement")) {
            return "budget_estimation";
        }
        if (snapshots.containsKey("attraction_filter")) {
            return "route_arrangement";
        }
        if (snapshots.containsKey("preference_analysis")) {
            return "attraction_filter";
        }
        return com.travel.planning.workflow.TravelWorkflowBuilder.RESUME_FULL;
    }

    /**
     * M6-51：过滤被污染的快照（Reactor Flux toString 泄漏如 "FluxFlatMap"、
     * 框架对象 toString 泄漏如 "com.alibaba."）——视为无快照，resume 回退更早断点
     * 或整图重跑，避免把脏字符串注入续跑上下文。
     */
    public static Map<String, String> sanitizeSnapshots(Map<String, String> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return snapshots == null ? Map.of() : snapshots;
        }
        Map<String, String> clean = new LinkedHashMap<>();
        snapshots.forEach((node, payload) -> {
            if (payload != null && !payload.isBlank()
                    && !payload.startsWith("Flux")
                    && !payload.startsWith("com.alibaba.")) {
                clean.put(node, payload);
            }
        });
        return clean;
    }

    /**
     * M6-51：构建 resume 消息上下文（用户请求 + 已有快照产物）。
     * 与 UserInputNode 的 messages 形态一致（单个 UserMessage），
     * 供 includeContents=true 的子 Agent 读取完整上下文。
     */
    public static UserMessage buildResumeMessage(String userInput, Map<String, String> snapshots) {
        StringBuilder ctx = new StringBuilder(userInput == null ? "" : userInput);
        appendResumeSection(ctx, "偏好分析", "preference_analysis", snapshots);
        appendResumeSection(ctx, "候选景点", "attraction_filter", snapshots);
        appendResumeSection(ctx, "每日行程", "route_arrangement", snapshots);
        appendResumeSection(ctx, "预算估算", "budget_estimation", snapshots);
        return new UserMessage(ctx.toString());
    }

    private static void appendResumeSection(StringBuilder ctx, String label, String nodeKey,
                                            Map<String, String> snapshots) {
        String payload = snapshots.get(nodeKey);
        if (payload != null && !payload.isBlank()) {
            ctx.append("\n\n【").append(label).append("】\n").append(payload);
        }
    }

    /** 从占位行重建请求上下文（clientRequestId 由调用方另行保留语义）。 */
    public static ItineraryGenerateRequestDTO rebuildRequest(Itinerary task) {
        ItineraryGenerateRequestDTO req = new ItineraryGenerateRequestDTO();
        req.setDestination(task.getDestination());
        req.setDays(task.getDays());
        req.setBudget(task.getBudget());
        req.setParty(task.getParty());
        req.setStartDate(task.getStartDate());
        try {
            if (task.getInterests() != null && !task.getInterests().isBlank()) {
                req.setInterests(JsonUtils.getMapper()
                        .readValue(task.getInterests(), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}));
            }
        } catch (Exception ignore) {
            // interests 解析失败按缺省处理（断点续跑不因此阻断）
        }
        return req;
    }

    /** 快照产物注入（String 形态——下游 toText 兼容；被跳过节点不再执行）。 */
    public static void injectSnapshot(Map<String, Object> initialState, String nodeKey,
                                      String stateKey, Map<String, String> snapshots) {
        String payload = snapshots.get(nodeKey);
        if (payload != null && !payload.isBlank()) {
            initialState.put(stateKey, payload);
        }
    }

    /** GENERATING 僵尸判定：updated_at 距今超过 zombie-minutes 视为死任务。 */
    public boolean isZombie(Itinerary task) {
        if (task.getUpdatedAt() == null) {
            return true; // 无时间戳保守视为僵尸（可 resume）
        }
        return task.getUpdatedAt().isBefore(java.time.LocalDateTime.now()
                .minusMinutes(Math.max(1, stateMachineProps.getZombieMinutes())));
    }
}
