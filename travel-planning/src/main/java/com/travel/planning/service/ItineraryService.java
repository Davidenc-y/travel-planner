package com.travel.planning.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.travel.common.dto.ItineraryGenerateRequestDTO;
import com.travel.common.dto.ItineraryResponseDTO;
import com.travel.common.dto.ItineraryResponseDTO.*;
import com.travel.common.entity.Itinerary;
import com.travel.common.enums.ItineraryStatus;
import com.travel.common.exception.BusinessException;
import com.travel.common.exception.ErrorCode;
import com.travel.common.exception.ItineraryGenerationException;
import com.travel.common.result.PageResult;
import com.travel.common.util.JsonUtils;
import com.travel.planning.memory.longterm.ProfileContextAssembler;
import com.travel.planning.memory.longterm.ProfilePort;
import com.travel.planning.memory.longterm.ProfileToolProvider;
import com.travel.planning.memory.knowledge.SessionContextChunker;
import com.travel.planning.memory.knowledge.SessionKnowledgeWriter;
import com.travel.planning.guard.GuardService;
import com.travel.planning.prompt.PromptTemplates;
import com.travel.planning.trace.TraceContext;
import com.travel.planning.repository.ItineraryMapper;
import com.travel.planning.workflow.TravelWorkflowBuilder;
import com.travel.aigateway.core.GatewayException;
import com.travel.aigateway.core.ModelRegistry;
import com.travel.aigateway.route.ModelRoutingContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 行程服务（M2-2 增强版）
 *
 * <p>M2-2 增强：</p>
 * <ul>
 *   <li>JSON 解析：将 content JSON 解析为 DayPlan 列表</li>
 *   <li>思维导图：调用 MindmapGenerator 生成结构化思维导图</li>
 *   <li>画像更新：行程生成后自动更新用户旅游画像</li>
 *   <li>估算费用：从 budgetEstimate JSON 提取 totalCost</li>
 *   <li>超时保护：工作流执行添加超时控制</li>
 * </ul>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItineraryService {

    /**
     * 工作流整体执行超时（秒）：硬性退出边界。
     * 防止 LLM 调用异常/重试异常导致请求无限悬挂（F23 修复，配合重试上限）。
     */
    private static final long MAX_EXECUTION_SECONDS = 300;

    /** M4-7（修复 4）：mindmap 兜底 LLM 调用超时（秒） */
    private static final long MINDMAP_TIMEOUT_SECONDS = 60;

    /**
     * 工作流执行专用线程池：使用虚拟线程（Java 21）。
     *
     * <p>F24 补强：原 CachedThreadPool 的线程为非 daemon，超时后任务仍会在后台继续执行，
     * 且非 daemon 线程会阻塞 JVM 优雅退出；虚拟线程为 daemon 且轻量，
     * 与 {@link CompletableFuture#cancel(boolean)} 配合可及时中断 graph.invoke 的阻塞等待。</p>
     */
    private static final ExecutorService WORKFLOW_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final ItineraryMapper itineraryMapper;
    private final TravelWorkflowBuilder workflowBuilder;
    private final ProfilePort profilePort;
    private final MindmapGenerator mindmapGenerator;
    private final ProfileContextAssembler profileContextAssembler;
    // Phase C/F78：行程知识按天切片异步写入会话知识库
    private final SessionContextChunker sessionContextChunker;
    private final SessionKnowledgeWriter sessionKnowledgeWriter;
    // F90：Agent 调用前安全防护（Prompt 注入等）
    private final GuardService guardService;
    // M3-20：Prompt 模板外置（P1-17）
    private final PromptTemplates promptTemplates;
    // M4-7（前置修复 3）：持久化拆独立 Service，修 @Transactional 自调用失效
    private final ItineraryPersistenceService persistenceService;
    // M4-8：节点快照读取（resume 断点判定）与状态机配置
    private final com.travel.planning.workflow.ItineraryTaskSnapshotPort snapshotPort;
    private final com.travel.planning.workflow.ItineraryStateMachineProperties stateMachineProps;
    // M7：模型注册表（D6：请求级 model 入口快速失败校验）
    private final ModelRegistry modelRegistry;

    /**
     * 生成行程（调用 StateGraph 工作流 + 持久化 + 画像更新）
     */
    public ItineraryResponseDTO generate(ItineraryGenerateRequestDTO req, Long userId) {
        validateModel(req.getModel());
        return ModelRoutingContext.runWith(req.getModel(),
                () -> generateInternal(req, userId));
    }

    private ItineraryResponseDTO generateInternal(ItineraryGenerateRequestDTO req, Long userId) {
        // F52：防御脏 userId（兜底 0 会导致 user_id=0 行程/画像）。
        if (userId == null || userId <= 0) {
            throw new BusinessException(40101, "用户未登录");
        }
        // F90：调用前安全防护（对目的地/兴趣等文本做注入检测）
        String guardInput = String.join(" ", req.getDestination(),
                req.getInterests() == null ? "" : String.join(",", req.getInterests()),
                req.getParty() == null ? "" : req.getParty());
        var guard = guardService.check(String.valueOf(userId), guardInput);
        if (!guard.allowed()) {
            throw new BusinessException(40302, guard.reason());
        }
        // F89：追溯上下文填充（user/session）
        if (TraceContext.active()) {
            TraceContext.Holder h = TraceContext.current();
            h.trace.setUserId(userId);
            h.trace.setSessionId(req.getSessionId());
            h.addPath("itinerary");
        }
        // 1. 幂等检查（M4-7 修复 1：补 userId 条件，防跨用户命中）
        Itinerary existing = itineraryMapper.findByClientRequestIdAndUser(
                req.getClientRequestId(), userId);
        if (existing != null) {
            log.info("幂等命中: clientRequestId={}, status={}", req.getClientRequestId(), existing.getStatus());
            // M4-8：状态机分派——FAILED/僵尸 GENERATING 走断点续跑；进行中拒绝；
            // 已完成（或状态机关闭时的 GENERATED）直接重放
            if (stateMachineProps.isEnabled()) {
                switch (String.valueOf(existing.getStatus())) {
                    case "GENERATED", "CONFIRMED" -> {
                        return toResponseDTO(existing);
                    }
                    case "GENERATING" -> {
                        if (isZombie(existing)) {
                            return resumeInternal(existing);
                        }
                        throw new BusinessException(40905, "行程正在生成中，请稍后重试或使用继续生成");
                    }
                    case "FAILED" -> {
                        return resumeInternal(existing);
                    }
                    default -> {
                        return toResponseDTO(existing);
                    }
                }
            }
            return toResponseDTO(existing);
        }

        // 2. 构建工作流初始状态（F50/Phase A：画像读取注入，偏好进入 preference 阶段）
        String profileContext = profileContextAssembler.assemble(profilePort.getOrCreate(userId));
        String userInput = buildUserInput(req);
        if (!profileContext.isBlank()) {
            userInput = profileContext + "\n\n" + userInput;
        }
        Map<String, Object> initialState = new HashMap<>();
        initialState.put("userInput", userInput);
        initialState.put("userId", userId);
        initialState.put("retryCount", 0);
        // B3-6/F73：检索 query 与输入文本分离——rag_retrieval 用结构化需求文本，
        // 画像前缀只进 messages 不进检索 query（避免画像全文 URL 编码超 Tomcat 头限制，
        // 22:59 实测 8082 "Request header is too large"）。
        // F73 补充：检索 query 只保留检索相关字段（目的地+兴趣），剔除 预算/出行人员/天数/
        // 开始日期——否则"出行人员：家庭"会被 knowledge 识别为景点类型 FAMILY，
        // city+type 过滤后 0 命中（23:08 实测）。
        initialState.put("retrievalQuery", buildRetrievalQuery(req));

        // M4-8：状态机开启——入口即插 GENERATING 占位（taskId 供快照包装器回填），
        // 幂等前移到占位之前；失败/超时进程内也可见（可 resume）
        Long taskId = null;
        Itinerary entity = buildEntity(req, userId, ItineraryStatus.GENERATED.name(), null, null, null);
        if (stateMachineProps.isEnabled()) {
            entity.setStatus(ItineraryStatus.GENERATING.name());
            try {
                persistenceService.insertGenerating(entity);
                taskId = entity.getId();
            } catch (DuplicateKeyException dke) {
                // 并发双发同 clientRequestId：转幂等重读（M4-7 修复 2）
                log.warn("行程并发双发幂等转重读: clientRequestId={}", req.getClientRequestId());
                Itinerary winner = itineraryMapper.findByClientRequestIdAndUser(
                        req.getClientRequestId(), userId);
                if (winner != null && "GENERATED".equals(winner.getStatus())) {
                    return toResponseDTO(winner);
                }
                throw new BusinessException(40905, "行程正在生成中，请稍后重试或使用继续生成");
            }
            initialState.put(com.travel.planning.workflow.SnapshotNodeWrapper.TASK_ID_KEY, taskId);
        }

        // 3. 执行工作流
        long start = System.currentTimeMillis();
        try {
            CompiledGraph graph = workflowBuilder.buildWorkflow();
            OverAllState finalState = executeGraph(graph, initialState);

            String itineraryJson = finalState.value("itinerary", "").toString();
            String mindmapJson = finalState.value("mindmap", "").toString();

            long cost = System.currentTimeMillis() - start;
            log.info("工作流执行完成: 耗时={}ms, itineraryLen={}, mindmapLen={}",
                    cost, itineraryJson.length(), mindmapJson.length());

            // 4. 提取估算费用
            BigDecimal estimatedCost = extractEstimatedCost(itineraryJson);

            // 5. 生成思维导图（如果工作流未生成）
            // M4-7（修复 4）：mindmap 兜底纳入统一超时治理——原实现是 300s orTimeout 之外
            // 的又一次同步 LLM 调用，超时保护有缺口
            String finalMindmap = mindmapJson;
            if (finalMindmap == null || finalMindmap.isBlank()) {
                finalMindmap = withTimeout(() -> {
                    MindmapData mindmap = mindmapGenerator.generate(
                            req.getDestination() + req.getDays() + "日游",
                            req.getDestination(), req.getDays(),
                            req.getBudget() != null ? req.getBudget().toString() : null,
                            itineraryJson);
                    return JsonUtils.toJson(mindmap);
                }, MINDMAP_TIMEOUT_SECONDS, "思维导图生成");
            }

            // 6. 持久化：状态机=终态更新占位行；关闭=一次性插入（M4-7 修复 2/3 语义保留）
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
                    // 并发双发同 clientRequestId：先到者已 persist，后者转幂等重读返回
                    log.warn("行程并发双发幂等转重读: clientRequestId={}", req.getClientRequestId());
                    Itinerary winner = itineraryMapper.findByClientRequestIdAndUser(
                            req.getClientRequestId(), userId);
                    if (winner != null) {
                        return toResponseDTO(winner);
                    }
                    throw dke;
                }
            }

            // M4-8（运行时回归 D1 修复）：占位行终态更新后同步回写内存实体，
            // 响应 DTO 的 status 才是 GENERATED（此前透出 GENERATING）
            entity.setStatus(ItineraryStatus.GENERATED.name());
            log.info("行程生成成功: id={}, destination={}, cost={}", entity.getId(), req.getDestination(), estimatedCost);

            // Phase C/F78（C1）：行程知识按天切片异步写入会话知识库（req.sessionId 存在时）
            if (req.getSessionId() != null && !req.getSessionId().isBlank()) {
                // M8-9：先按行程 id 前缀删除旧版本（resume/重生成覆盖，避免新旧天块混叠）
                sessionKnowledgeWriter.deleteBySeqPrefix(
                        req.getSessionId(), "itin:" + entity.getId() + ":");
                sessionKnowledgeWriter.writeAsync(req.getSessionId(),
                        sessionContextChunker.chunkItinerary(req.getSessionId(), itineraryJson, entity.getId()));
            }

            // 7. 更新用户画像（非阻塞，失败不影响主流程）
            // F53：画像更新补充 预算 + 出行人员（→ 出行风格）。
            profilePort.recordTrip(userId, req.getDestination(),
                    JsonUtils.toJson(req.getInterests()), entity.getTitle(),
                    req.getBudget(), req.getParty());

            return toResponseDTO(entity);

        } catch (Exception e) {
            // M4-8：失败/超时 → 占位行置 FAILED（保留快照供 resume）；置态失败不吞原异常
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

    /**
     * M4-9/P1-5：断点续跑——按最新快照集确定断点，共用前缀子图缓存续跑剩余节点。
     *
     * <p>守卫：仅 FAILED 或僵尸 GENERATING 可续（已 GENERATED 返回 40903、
     * 进行中返回 40905）；快照缺失回退整图重跑（不抛错、幂等键沿用）。</p>
     */
    public ItineraryResponseDTO resume(Long id, Long userId) {
        return ModelRoutingContext.runWith(null, () -> resumeInternal(id, userId));
    }

    private ItineraryResponseDTO resumeInternal(Long id, Long userId) {
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
        return resumeInternal(task);
    }

    /** 幂等命中/显式 resume 共用：快照断点判定 + 子图续跑 + 终态更新 */
    private ItineraryResponseDTO resumeInternal(Itinerary task) {
        // M6-51：过滤被污染的快照（Flux toString 泄漏），避免脏字符串注入续跑上下文
        Map<String, String> snapshots = sanitizeSnapshots(snapshotPort.loadLatestByTask(task.getId()));
        String resumeFrom = resolveResumeFrom(snapshots);
        log.info("行程断点续跑: taskId={}, resumeFrom={}, 快照节点={}",
                task.getId(), resumeFrom, snapshots.keySet());

        // 从占位行重建请求上下文（clientRequestId/目的地/天数/预算等已落库）
        ItineraryGenerateRequestDTO req = rebuildRequest(task);
        String profileContext = profileContextAssembler.assemble(profilePort.getOrCreate(task.getUserId()));
        String userInput = buildUserInput(req);
        if (!profileContext.isBlank()) {
            userInput = profileContext + "\n\n" + userInput;
        }
        Map<String, Object> initialState = new HashMap<>();
        initialState.put("userInput", userInput);
        initialState.put("userId", task.getUserId());
        initialState.put("retryCount", 0);
        initialState.put("retrievalQuery", buildRetrievalQuery(req));
        initialState.put(com.travel.planning.workflow.SnapshotNodeWrapper.TASK_ID_KEY, task.getId());
        // 快照产物注入（String 形态——下游 toText 兼容；被跳过节点不再执行）
        injectSnapshot(initialState, "preference_analysis", "preference", snapshots);
        injectSnapshot(initialState, "attraction_filter", "attractions", snapshots);
        injectSnapshot(initialState, "route_arrangement", "routePlan", snapshots);
        injectSnapshot(initialState, "budget_estimation", "budgetEstimate", snapshots);
        // M6-51：resume 子图跳过 user_input/rag_retrieval——父 state 无 messages，
        // 子 Agent（asNode includeContents=true）只能看到 instruction，拿不到路线/
        // 偏好上下文（预算估算曾输出全 0 "未提供具体行程路线"）。重建组合消息上下文。
        initialState.put("messages", buildResumeMessage(userInput, snapshots));

        long start = System.currentTimeMillis();
        try {
            CompiledGraph graph = workflowBuilder.buildWorkflow(resumeFrom);
            OverAllState finalState = executeGraph(graph, initialState);
            String itineraryJson = finalState.value("itinerary", "").toString();
            String mindmapJson = finalState.value("mindmap", "").toString();
            BigDecimal estimatedCost = extractEstimatedCost(itineraryJson);
            String finalMindmap = mindmapJson;
            if (finalMindmap == null || finalMindmap.isBlank()) {
                finalMindmap = withTimeout(() -> {
                    MindmapData mindmap = mindmapGenerator.generate(
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
            // 续跑成功同样更新画像（与 generate 成功路径一致；sessionId 未落行程表，
            // 会话知识切片由下次同会话生成补写，此处不阻塞）
            try {
                ItineraryGenerateRequestDTO rebuilt = rebuildRequest(task);
                profilePort.recordTrip(task.getUserId(), task.getDestination(),
                        JsonUtils.toJson(rebuilt.getInterests()), task.getTitle(),
                        task.getBudget(), task.getParty());
            } catch (Exception pe) {
                log.warn("续跑后画像更新失败（不影响主流程）: {}", pe.getMessage());
            }
            return toResponseDTO(task);
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
            throw new ItineraryGenerationException(buildUpstreamMessage(e), e);
        }
    }

    /** 断点=最新完成节点的下一个执行单元（无快照→整图重跑） */
    static String resolveResumeFrom(Map<String, String> snapshots) {
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
    static Map<String, String> sanitizeSnapshots(Map<String, String> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return snapshots == null ? java.util.Map.of() : snapshots;
        }
        Map<String, String> clean = new java.util.LinkedHashMap<>();
        snapshots.forEach((node, payload) -> {
            if (payload != null && !payload.isBlank()
                    && !payload.startsWith("Flux")
                    && !payload.startsWith("com.alibaba.")) {
                clean.put(node, payload);
            }
        });
        return clean;
    }

    private static void injectSnapshot(Map<String, Object> initialState, String nodeKey,
                                       String stateKey, Map<String, String> snapshots) {
        String payload = snapshots.get(nodeKey);
        if (payload != null && !payload.isBlank()) {
            initialState.put(stateKey, payload);
        }
    }

    /** M7 D6：未知/禁用/不可选模型 → 40005，不静默回退。 */
    private void validateModel(String model) {
        if (model == null || model.isBlank()) {
            return;
        }
        try {
            modelRegistry.requireSelectable(model);
        } catch (GatewayException e) {
            throw new BusinessException(ErrorCode.MODEL_NOT_FOUND.code(),
                    ErrorCode.MODEL_NOT_FOUND.message() + ": " + model);
        }
    }

    /**
     * M6-51：构建 resume 消息上下文（用户请求 + 已有快照产物）。
     * 与 UserInputNode 的 messages 形态一致（单个 UserMessage），
     * 供 includeContents=true 的子 Agent 读取完整上下文。
     */
    static UserMessage buildResumeMessage(String userInput, Map<String, String> snapshots) {
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

    /** GENERATING 僵尸判定：updated_at 距今超过 zombie-minutes 视为死任务 */
    private boolean isZombie(Itinerary task) {
        if (task.getUpdatedAt() == null) {
            return true; // 无时间戳保守视为僵尸（可 resume）
        }
        return task.getUpdatedAt().isBefore(
                java.time.LocalDateTime.now().minusMinutes(Math.max(1, stateMachineProps.getZombieMinutes())));
    }

    private static ItineraryGenerateRequestDTO rebuildRequest(Itinerary task) {
        ItineraryGenerateRequestDTO req = new ItineraryGenerateRequestDTO();
        req.setDestination(task.getDestination());
        req.setDays(task.getDays());
        req.setBudget(task.getBudget());
        req.setParty(task.getParty());
        req.setStartDate(task.getStartDate());
        try {
            if (task.getInterests() != null && !task.getInterests().isBlank()) {
                req.setInterests(com.travel.common.util.JsonUtils.getMapper()
                        .readValue(task.getInterests(), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}));
            }
        } catch (Exception ignore) {
            // interests 解析失败按缺省处理（断点续跑不因此阻断）
        }
        return req;
    }

    private static Itinerary buildEntity(ItineraryGenerateRequestDTO req, Long userId, String status,
                                         String content, String mindmap, BigDecimal estimatedCost) {
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
        return entity;
    }

    /**
     * M4-8：图执行统一入口（orTimeout 300s + cancel 中断，F23/F24 语义不变）。
     */
    private OverAllState executeGraph(CompiledGraph graph, Map<String, Object> initialState) {
        CompletableFuture<Optional<OverAllState>> future = null;
        try {
            // F64/B2：把 userId 写入 RunnableConfig.metadata，供画像工具从 ToolContext 读取
            Object uid = initialState.get("userId");
            RunnableConfig config = RunnableConfig.builder()
                    .addMetadata(com.travel.planning.memory.longterm.ProfileToolProvider.USER_ID_METADATA_KEY,
                            uid == null ? 0L : uid)
                    // M6-51：AgentLlmNode 默认按 metadata("_stream_") 走流式（输出 Flux），
                    // 导致 SnapshotNodeWrapper 快照 payload 泄漏为 "FluxFlatMap"（toString）。
                    // 显式关闭流式 → 节点输出 AssistantMessage，快照可正确归一化业务 JSON。
                    .addMetadata("_stream_", false)
                    .build();
            future = CompletableFuture.supplyAsync(() -> graph.invoke(initialState, config), WORKFLOW_EXECUTOR);
            return future.orTimeout(MAX_EXECUTION_SECONDS, TimeUnit.SECONDS)
                    .get()
                    .orElseThrow(() -> new ItineraryGenerationException("工作流未返回最终状态"));
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof TimeoutException) {
                // F24 补强：超时后立即 cancel(true) 中断后台 graph.invoke
                if (future != null) {
                    future.cancel(true);
                }
                log.error("工作流执行超时（>{}s）", MAX_EXECUTION_SECONDS);
                throw new ItineraryGenerationException(
                        "行程生成超时（超过 " + MAX_EXECUTION_SECONDS + " 秒），请稍后重试", ee);
            }
            if (cause instanceof RuntimeException re) {
                throw re; // 保留原始异常（含 DashScope 上游错误）
            }
            throw new ItineraryGenerationException(
                    cause != null ? cause.getMessage() : "行程生成失败", ee);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ItineraryGenerationException("行程生成被中断", ie);
        }
    }

    /**
     * M4-7（修复 4）：带硬超时执行辅助 LLM 调用（虚拟线程 + orTimeout + cancel）。
     * 与主工作流同样的超时语义（F23/F24），供 mindmap 兜底等图外 LLM 调用复用。
     */
    private <T> T withTimeout(java.util.function.Supplier<T> task, long seconds, String what) {
        CompletableFuture<T> future = CompletableFuture.supplyAsync(task, WORKFLOW_EXECUTOR);
        try {
            return future.orTimeout(seconds, TimeUnit.SECONDS).get();
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof TimeoutException) {
                future.cancel(true);
                throw new ItineraryGenerationException(what + "超时（超过 " + seconds + " 秒）", ee);
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new ItineraryGenerationException(
                    what + "失败: " + (cause != null ? cause.getMessage() : "unknown"), ee);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ItineraryGenerationException(what + "被中断", ie);
        }
    }

    /**
     * 沿异常链查找 DashScope 的 WebClient 响应异常，把上游响应体（如
     * AllocationQuota.FreeTierOnly）透出到业务异常 message，便于快速定位配额/鉴权问题。
     */
    private static String buildUpstreamMessage(Throwable e) {
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

    /**
     * 查询行程详情（含 dayPlans + mindmap 解析）
     */
    public ItineraryResponseDTO getById(Long id) {
        Itinerary entity = itineraryMapper.selectById(id);
        if (entity == null) {
            throw new ItineraryGenerationException("行程不存在: " + id);
        }
        return toResponseDTO(entity);
    }

    /**
     * 分页查询用户行程
     */
    public PageResult<ItineraryResponseDTO> listByUserId(Long userId, int page, int size) {
        int offset = (page - 1) * size;
        List<Itinerary> list = itineraryMapper.findByUserId(userId, offset, size);
        long total = itineraryMapper.countByUserId(userId);
        return PageResult.of(list.stream().map(this::toResponseDTO).toList(), total, page, size);
    }

    /**
     * 删除行程
     */
    public void delete(Long id) {
        itineraryMapper.deleteById(id);
        log.info("行程删除: id={}", id);
    }

    // ==================== 内部方法 ====================

    /**
     * 构建用户输入文本
     */
    private String buildUserInput(ItineraryGenerateRequestDTO req) {
        return promptTemplates.itineraryUserInput().formatted(
                req.getDestination(),
                req.getDays(),
                req.getBudget() != null ? req.getBudget() + "元" : "不限",
                req.getInterests() != null ? req.getInterests() : "不限",
                req.getParty() != null ? req.getParty() : "不限",
                req.getStartDate() != null ? req.getStartDate() : "未指定");
    }

    /**
     * F73 补充：检索专用文本（只含检索相关字段），避免行程参数污染检索意图。
     */
    private static String buildRetrievalQuery(ItineraryGenerateRequestDTO req) {
        StringBuilder sb = new StringBuilder("目的地：").append(req.getDestination());
        if (req.getInterests() != null && !req.getInterests().isEmpty()) {
            sb.append("\n兴趣：").append(String.join("、", req.getInterests()));
        }
        return sb.toString();
    }

    /**
     * 从行程 JSON 中提取估算总费用
     */
    private BigDecimal extractEstimatedCost(String itineraryJson) {
        try {
            Map<String, Object> content = JsonUtils.fromJson(itineraryJson, Map.class);
            if (content == null) return null;

            // 尝试从 budgetEstimate.totalCost 提取
            Object budgetEstimate = content.get("budgetEstimate");
            if (budgetEstimate instanceof Map) {
                Object totalCost = ((Map<String, Object>) budgetEstimate).get("totalCost");
                if (totalCost != null) {
                    return new BigDecimal(totalCost.toString());
                }
            }
        } catch (Exception e) {
            log.warn("提取估算费用失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Entity → ResponseDTO（含 dayPlans + mindmap 解析）
     */
    private ItineraryResponseDTO toResponseDTO(Itinerary entity) {
        ItineraryResponseDTO dto = ItineraryResponseDTO.builder()
                .id(entity.getId())
                .title(entity.getTitle())
                .destination(entity.getDestination())
                .days(entity.getDays())
                .estimatedCost(entity.getEstimatedCost())
                .generatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null)
                .status(entity.getStatus())
                // M6-52：权威可续标志——FAILED 或僵尸 GENERATING 可续；
                // 非僵尸 GENERATING 前端应禁用"继续生成"（避免并发双跑）
                .resumable(isResumable(entity))
                .build();

        // 解析 content JSON → dayPlans
        if (entity.getContent() != null && !entity.getContent().isBlank()) {
            try {
                Map<String, Object> content = JsonUtils.fromJson(entity.getContent(), Map.class);
                if (content != null && content.containsKey("routePlan")) {
                    Object routePlan = content.get("routePlan");
                    if (routePlan instanceof Map) {
                        Object days = ((Map<String, Object>) routePlan).get("days");
                        if (days instanceof List) {
                            dto.setDayPlans(parseDayPlans((List<?>) days));
                        }
                    }
                }
                // 预算分配明细透出（M2-5 输出优化）：content.budgetEstimate -> dto.budgetBreakdown
                if (content != null && content.containsKey("budgetEstimate")) {
                    dto.setBudgetBreakdown(parseBudgetBreakdown(content.get("budgetEstimate")));
                }
            } catch (Exception e) {
                log.warn("解析 dayPlans 失败: itineraryId={}", entity.getId());
            }
        }

        // 解析 mindmapData JSON → MindmapData
        if (entity.getMindmapData() != null && !entity.getMindmapData().isBlank()) {
            try {
                MindmapData mindmap = JsonUtils.fromJson(entity.getMindmapData(), MindmapData.class);
                dto.setMindmap(mindmap);
            } catch (Exception e) {
                log.warn("解析 mindmap 失败: itineraryId={}", entity.getId());
            }
        }

        return dto;
    }

    /**
     * M6-52：行程是否可断点续跑（与 resume 端点守卫同口径）。
     */
    private boolean isResumable(Itinerary entity) {
        if (entity == null || entity.getStatus() == null) {
            return false;
        }
        String status = entity.getStatus();
        return "FAILED".equals(status)
                || ("GENERATING".equals(status) && isZombie(entity));
    }

    /**
     * 将 content.budgetEstimate（Map/JSON）解析为 {@link BudgetBreakdown}。
     * 字段缺失或类型异常时按 null 容错，不影响主流程。
     */
    @SuppressWarnings("unchecked")
    private BudgetBreakdown parseBudgetBreakdown(Object budgetEstimate) {
        if (!(budgetEstimate instanceof Map)) {
            return null;
        }
        Map<String, Object> map = (Map<String, Object>) budgetEstimate;
        return BudgetBreakdown.builder()
                .ticketCost(toBigDecimal(map.get("ticketCost")))
                .mealCost(toBigDecimal(map.get("mealCost")))
                .transportCost(toBigDecimal(map.get("transportCost")))
                .hotelCost(toBigDecimal(map.get("hotelCost")))
                .otherCost(toBigDecimal(map.get("otherCost")))
                .totalCost(toBigDecimal(map.get("totalCost")))
                .perPersonCost(toBigDecimal(map.get("perPersonCost")))
                .currency(map.get("currency") != null ? map.get("currency").toString() : null)
                .notes(map.get("notes") != null ? map.get("notes").toString() : null)
                .build();
    }

    /**
     * 将 Number / String 安全转为 BigDecimal，null 或非法值返回 null。
     */
    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        try {
            return new BigDecimal(value.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析每日计划列表
     */
    @SuppressWarnings("unchecked")
    private List<DayPlan> parseDayPlans(List<?> dayList) {
        List<DayPlan> result = new ArrayList<>();
        for (Object item : dayList) {
            if (item instanceof Map) {
                Map<String, Object> dayMap = (Map<String, Object>) item;
                DayPlan day = DayPlan.builder()
                        .day((Integer) dayMap.get("day"))
                        .date((String) dayMap.get("date"))
                        .summary((String) dayMap.get("summary"))
                        .transportMode((String) dayMap.get("transportMode"))
                        .hotelSuggestion((String) dayMap.get("hotelSuggestion"))
                        .build();

                // 解析景点访问列表
                Object attractions = dayMap.get("attractions");
                if (attractions instanceof List) {
                    List<AttractionVisit> visits = new ArrayList<>();
                    for (Object attr : (List<?>) attractions) {
                        if (attr instanceof Map) {
                            Map<String, Object> attrMap = (Map<String, Object>) attr;
                            visits.add(AttractionVisit.builder()
                                    .name((String) attrMap.get("name"))
                                    .timeSlot((String) attrMap.get("timeSlot"))
                                    .cost(toBigDecimal(attrMap.get("cost")))
                                    .notes((String) attrMap.get("notes"))
                                    .build());
                        }
                    }
                    day.setAttractions(visits);
                }
                result.add(day);
            }
        }
        return result;
    }
}
