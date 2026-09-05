package com.travel.planning.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.travel.common.dto.ItineraryGenerateRequestDTO;
import com.travel.common.dto.ItineraryResponseDTO;
import com.travel.common.entity.Itinerary;
import com.travel.common.entity.TravelProfile;
import com.travel.common.enums.ItineraryStatus;
import com.travel.common.exception.BusinessException;
import com.travel.common.exception.ItineraryGenerationException;
import com.travel.common.util.JsonUtils;
import com.travel.planning.guard.GuardService;
import com.travel.planning.memory.longterm.ProfileContextAssembler;
import com.travel.planning.memory.longterm.ProfilePort;
import com.travel.planning.prompt.PromptTemplates;
import com.travel.planning.repository.ItineraryMapper;
import com.travel.planning.trace.TraceContext;
import com.travel.planning.weather.WeatherContextBuilder;
import com.travel.planning.workflow.ItineraryStateMachineProperties;
import com.travel.planning.workflow.TravelWorkflowBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * M10-1c：行程生成编排器（自 ItineraryService 拆出）。
 *
 * <p>职责：安全防护 → 追溯填充 → 幂等分派 → 画像/初始状态构建 → GENERATING 占位 →
 * 图执行 → 费用提取/mindmap 兜底 → 终态持久化 → 会话天块 → 画像更新。
 * 保持 F52/F90/M4-7/M4-8/M8-9/M6-51 全部语义。</p>
 */
@Slf4j
@Component
public class ItineraryGenerationOrchestrator {

    /** M4-7（修复 4）：mindmap 兜底 LLM 调用超时（秒） */
    private static final long MINDMAP_TIMEOUT_SECONDS = 60;

    private final ItineraryMapper itineraryMapper;
    private final TravelWorkflowBuilder workflowBuilder;
    private final ProfilePort profilePort;
    private final MindmapGenerator mindmapGenerator;
    private final ProfileContextAssembler profileContextAssembler;
    private final GuardService guardService;
    private final PromptTemplates promptTemplates;
    private final ItineraryPersistenceService persistenceService;
    private final ItineraryStateMachineProperties stateMachineProps;
    private final ItineraryDtoAssembler dtoAssembler;
    private final ItineraryGraphExecutor graphExecutor;
    private final ItineraryResumeCoordinator resumeCoordinator;
    private final ItinerarySliceWriter sliceWriter;
    private final WeatherContextBuilder weatherContextBuilder;

    public ItineraryGenerationOrchestrator(ItineraryMapper itineraryMapper,
                                           TravelWorkflowBuilder workflowBuilder,
                                           ProfilePort profilePort,
                                           MindmapGenerator mindmapGenerator,
                                           ProfileContextAssembler profileContextAssembler,
                                           GuardService guardService,
                                           PromptTemplates promptTemplates,
                                           ItineraryPersistenceService persistenceService,
                                           ItineraryStateMachineProperties stateMachineProps,
                                           ItineraryDtoAssembler dtoAssembler,
                                           ItineraryGraphExecutor graphExecutor,
                                           ItineraryResumeCoordinator resumeCoordinator,
                                           ItinerarySliceWriter sliceWriter,
                                           WeatherContextBuilder weatherContextBuilder) {
        this.itineraryMapper = itineraryMapper;
        this.workflowBuilder = workflowBuilder;
        this.profilePort = profilePort;
        this.mindmapGenerator = mindmapGenerator;
        this.profileContextAssembler = profileContextAssembler;
        this.guardService = guardService;
        this.promptTemplates = promptTemplates;
        this.persistenceService = persistenceService;
        this.stateMachineProps = stateMachineProps;
        this.dtoAssembler = dtoAssembler;
        this.graphExecutor = graphExecutor;
        this.resumeCoordinator = resumeCoordinator;
        this.sliceWriter = sliceWriter;
        this.weatherContextBuilder = weatherContextBuilder;
    }

    /** 生成主流程（model 校验与 ModelRoutingContext 由入口 Service 负责）。 */
    public ItineraryResponseDTO generate(ItineraryGenerateRequestDTO req, Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(40101, "用户未登录");
        }
        String guardInput = String.join(" ", req.getDestination(),
                req.getInterests() == null ? "" : String.join(",", req.getInterests()),
                req.getParty() == null ? "" : req.getParty());
        var guard = guardService.check(String.valueOf(userId), guardInput);
        if (!guard.allowed()) {
            throw new BusinessException(40302, guard.reason());
        }
        if (TraceContext.active()) {
            TraceContext.Holder h = TraceContext.current();
            h.trace.setUserId(userId);
            h.trace.setSessionId(req.getSessionId());
            h.addPath("itinerary");
        }

        Itinerary existing = itineraryMapper.findByClientRequestIdAndUser(
                req.getClientRequestId(), userId);
        if (existing != null) {
            log.info("幂等命中: clientRequestId={}, status={}",
                    req.getClientRequestId(), existing.getStatus());
            if (stateMachineProps.isEnabled()) {
                switch (String.valueOf(existing.getStatus())) {
                    case "GENERATED", "CONFIRMED" -> {
                        return toDto(existing);
                    }
                    case "GENERATING" -> {
                        if (resumeCoordinator.isZombie(existing)) {
                            return resumeCoordinator.resumeExisting(existing);
                        }
                        throw new BusinessException(40905, "行程正在生成中，请稍后重试或使用继续生成");
                    }
                    case "FAILED" -> {
                        return resumeCoordinator.resumeExisting(existing);
                    }
                    default -> {
                        return toDto(existing);
                    }
                }
            }
            return toDto(existing);
        }

        TravelProfile profile = profilePort.getOrCreate(userId);
        String profileContext = profileContextAssembler.assemble(profile);
        String userInput = buildUserInput(req);
        if (!profileContext.isBlank()) {
            userInput = profileContext + "\n\n" + userInput;
        }
        // M15-1：出行天气确定性注入（weather.enabled=false 时为空串，行为等价）
        String weatherContext = weatherContextBuilder.build(
                req.getDestination(), req.getStartDate(), req.getDays());
        if (weatherContext != null && !weatherContext.isBlank()) {
            userInput = weatherContext + "\n\n" + userInput;
        }
        Map<String, Object> initialState = new HashMap<>();
        initialState.put("userInput", userInput);
        initialState.put("userId", userId);
        initialState.put("retryCount", 0);
        initialState.put("retrievalQuery", buildRetrievalQuery(req));
        // M14-1b：消费水平/出行人数确定性下传（供 itinerary_optimize 餐费硬约束）
        if (profile != null && profile.getConsumeLevel() != null) {
            initialState.put("consumeLevel", profile.getConsumeLevel());
        }
        if (req.getParty() != null && !req.getParty().isBlank()) {
            initialState.put("party", req.getParty());
        }

        Long taskId = null;
        Itinerary entity = buildEntity(req, userId, ItineraryStatus.GENERATED.name(),
                null, null, null);
        if (stateMachineProps.isEnabled()) {
            entity.setStatus(ItineraryStatus.GENERATING.name());
            try {
                persistenceService.insertGenerating(entity);
                taskId = entity.getId();
            } catch (DuplicateKeyException dke) {
                log.warn("行程并发双发幂等转重读: clientRequestId={}", req.getClientRequestId());
                Itinerary winner = itineraryMapper.findByClientRequestIdAndUser(
                        req.getClientRequestId(), userId);
                if (winner != null && "GENERATED".equals(winner.getStatus())) {
                    return toDto(winner);
                }
                throw new BusinessException(40905, "行程正在生成中，请稍后重试或使用继续生成");
            }
            initialState.put(com.travel.planning.workflow.SnapshotNodeWrapper.TASK_ID_KEY, taskId);
        }

        long start = System.currentTimeMillis();
        try {
            CompiledGraph graph = workflowBuilder.buildWorkflow();
            OverAllState finalState = graphExecutor.execute(graph, initialState);
            String itineraryJson = finalState.value("itinerary", "").toString();
            String mindmapJson = finalState.value("mindmap", "").toString();
            long cost = System.currentTimeMillis() - start;
            log.info("工作流执行完成: 耗时={}ms, itineraryLen={}, mindmapLen={}",
                    cost, itineraryJson.length(), mindmapJson.length());

            BigDecimal estimatedCost = dtoAssembler.estimatedCostFrom(itineraryJson);
            String finalMindmap = mindmapJson;
            if (finalMindmap == null || finalMindmap.isBlank()) {
                finalMindmap = graphExecutor.withTimeout(() -> {
                    ItineraryResponseDTO.MindmapData mindmap = mindmapGenerator.generate(
                            req.getDestination() + req.getDays() + "日游",
                            req.getDestination(), req.getDays(),
                            req.getBudget() != null ? req.getBudget().toString() : null,
                            itineraryJson);
                    return JsonUtils.toJson(mindmap);
                }, MINDMAP_TIMEOUT_SECONDS, "思维导图生成");
            }

            entity.setContent(itineraryJson);
            entity.setMindmapData(finalMindmap);
            entity.setEstimatedCost(estimatedCost);
            if (stateMachineProps.isEnabled()) {
                persistenceService.updateCompleted(taskId, ItineraryStatus.GENERATED.name(),
                        itineraryJson, finalMindmap, estimatedCost);
            } else {
                try {
                    persistenceService.insert(entity);
                } catch (DuplicateKeyException dke) {
                    log.warn("行程并发双发幂等转重读: clientRequestId={}", req.getClientRequestId());
                    Itinerary winner = itineraryMapper.findByClientRequestIdAndUser(
                            req.getClientRequestId(), userId);
                    if (winner != null) {
                        return toDto(winner);
                    }
                    throw dke;
                }
            }
            entity.setStatus(ItineraryStatus.GENERATED.name());
            log.info("行程生成成功: id={}, destination={}, cost={}",
                    entity.getId(), req.getDestination(), estimatedCost);

            sliceWriter.writeAfterGenerated(req.getSessionId(), entity.getId(), itineraryJson);
            profilePort.recordTrip(userId, req.getDestination(),
                    JsonUtils.toJson(req.getInterests()), entity.getTitle(),
                    req.getBudget(), req.getParty());
            return dtoAssembler.toResponseDTO(entity, false);
        } catch (Exception e) {
            if (stateMachineProps.isEnabled() && taskId != null) {
                try {
                    persistenceService.updateStatus(taskId, ItineraryStatus.FAILED.name());
                } catch (Exception markEx) {
                    log.warn("行程失败状态标记异常: taskId={}, {}", taskId, markEx.getMessage());
                }
            }
            if (e instanceof ItineraryGenerationException ige) {
                throw ige;
            }
            log.error("行程生成失败: {}", e.getMessage(), e);
            throw new ItineraryGenerationException(buildUpstreamMessage(e), e);
        }
    }

    /** Entity → DTO（复用 ResumeCoordinator 的可续口径）。 */
    private ItineraryResponseDTO toDto(Itinerary entity) {
        return dtoAssembler.toResponseDTO(entity, resumeCoordinator.isResumable(entity));
    }

    private String buildUserInput(ItineraryGenerateRequestDTO req) {
        return promptTemplates.itineraryUserInput().formatted(
                req.getDestination(),
                req.getDays(),
                req.getBudget() != null ? req.getBudget() + "元" : "不限",
                req.getInterests() != null ? req.getInterests() : "不限",
                req.getParty() != null ? req.getParty() : "不限",
                req.getStartDate() != null ? req.getStartDate() : "未指定");
    }

    private static String buildRetrievalQuery(ItineraryGenerateRequestDTO req) {
        StringBuilder sb = new StringBuilder("目的地：").append(req.getDestination());
        if (req.getInterests() != null && !req.getInterests().isEmpty()) {
            sb.append("\n兴趣：").append(String.join("、", req.getInterests()));
        }
        return sb.toString();
    }

    private static Itinerary buildEntity(ItineraryGenerateRequestDTO req, Long userId,
                                         String status, String content, String mindmap,
                                         BigDecimal estimatedCost) {
        Itinerary entity = new Itinerary();
        entity.setUserId(userId);
        entity.setDestination(req.getDestination());
        entity.setDays(req.getDays());
        entity.setBudget(req.getBudget());
        entity.setInterests(JsonUtils.toJson(req.getInterests()));
        entity.setParty(req.getParty());
        entity.setStartDate(req.getStartDate());
        entity.setStatus(status);
        entity.setTitle(req.getDestination() + req.getDays() + "日游");
        entity.setContent(content);
        entity.setMindmapData(mindmap);
        entity.setEstimatedCost(estimatedCost);
        entity.setClientRequestId(req.getClientRequestId());
        entity.setSessionId(req.getSessionId());
        return entity;
    }

    /** 沿异常链查找 DashScope WebClient 响应异常并透出上游响应体。 */
    static String buildUpstreamMessage(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof WebClientResponseException wcre) {
                String body = wcre.getResponseBodyAsString();
                if (body != null && !body.isBlank()) {
                    return cur.getMessage() + " | upstreamBody="
                            + body.substring(0, Math.min(300, body.length()));
                }
            }
            cur = cur.getCause();
        }
        return e != null ? e.getMessage() : "unknown";
    }
}
