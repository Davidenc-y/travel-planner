package com.travel.planning.agent.supervisor;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.travel.planning.agent.attraction.AttractionFilterAgent;
import com.travel.planning.agent.budget.BudgetEstimationAgent;
import com.travel.planning.agent.preference.PreferenceAnalysisAgent;
import com.travel.planning.agent.route.RouteArrangementAgent;
import com.travel.planning.config.AiModelConfig;
import com.travel.planning.memory.longterm.ProfileToolProvider;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.UUID;

/**
 * 旅游行程规划总协调器
 *
 * <p>使用 SupervisorAgent 编排 4 个子 Agent，动态调度生成行程：</p>
 * <ol>
 *   <li>preference_analysis: 提取偏好结构化数据</li>
 *   <li>attraction_filter: 筛选匹配景点</li>
 *   <li>route_arrangement: 编排每日路线</li>
 *   <li>budget_estimation: 估算总费用</li>
 * </ol>
 *
 * <p>调度策略：SupervisorAgent 由 LLM 决定调用顺序，支持多步骤循环路由。
 * 正常流程为顺序执行 1→2→3→4，若预算超支可回退到 2 重新筛选。</p>
 *
 * <p>注意：SupervisorAgent.instruction() 被库标记为 deprecated，
 * 但目前无替代 API，与 interview-system 保持一致使用 @SuppressWarnings。</p>
 *
 * <p>F26 修复：executePlanning 必须执行 SupervisorAgent 整图（supervisor.invoke），
 * 而非 supervisor.getMainAgent().call()——那只会返回路由决策（如 ["preference_analysis"]）。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Component
@SuppressWarnings("deprecation")
public class TravelSupervisorAgent {

    private final ChatModel chatModel;
    private final PreferenceAnalysisAgent prefAgent;
    private final AttractionFilterAgent attrAgent;
    private final RouteArrangementAgent routeAgent;
    private final BudgetEstimationAgent budgetAgent;

    private final TokenUsageInterceptor tokenUsageInterceptor;

    private SupervisorAgent supervisor;

    /**
     * 整体执行超时（秒）：硬性退出边界，防止路由循环/LLM 卡死时请求无限悬挂
     * （F26，镜像 ItineraryService 的 F24 超时+取消模式）。
     */
    private static final long MAX_EXECUTION_SECONDS = 300;

    /**
     * 父图迭代上限：正常流程约 14 次节点执行，含预算回退约 25 次；
     * 50 次作为有界安全网（默认 100 在 LLM 调用下空转过久）。
     */
    private static final int SUPERVISOR_RECURSION_LIMIT = 50;

    /**
     * Supervisor 执行专用虚拟线程池（Java 21，daemon），配合
     * {@link CompletableFuture#cancel(boolean)} 及时中断阻塞的 graph.invoke。
     */
    private static final ExecutorService SUPERVISOR_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    public TravelSupervisorAgent(ChatModel chatModel,
                                  PreferenceAnalysisAgent prefAgent,
                                  AttractionFilterAgent attrAgent,
                                  RouteArrangementAgent routeAgent,
                                  BudgetEstimationAgent budgetAgent,
                                  TokenUsageInterceptor tokenUsageInterceptor) {
        this.chatModel = chatModel;
        this.prefAgent = prefAgent;
        this.attrAgent = attrAgent;
        this.routeAgent = routeAgent;
        this.budgetAgent = budgetAgent;
        this.tokenUsageInterceptor = tokenUsageInterceptor;
    }

    @PostConstruct
    public void init() throws Exception {
        try {
            // 创建 mainAgent（路由决策者）— Spring AI Alibaba 1.1.2.0 必须设置
            ReactAgent mainAgent = ReactAgent.builder()
                    .name("travel_supervisor_main")
                    .model(chatModel)
                    .description("旅游行程规划总协调器,负责路由决策")
                    .systemPrompt("""
                            你是一个智能的旅游行程规划监督者。
                            可用的子Agent:
                            - preference_analysis(偏好分析): 从用户输入中提取目的地、天数、预算、兴趣等结构化数据
                            - attraction_filter(景点筛选): 根据偏好筛选匹配的景点
                            - route_arrangement(路线编排): 编排每日行程路线
                            - budget_estimation(预算估算): 估算总费用

                            ## 路由决策输出格式
                            当需要做出路由决策时,请以 JSON 数组格式输出:
                            - 选择单个子Agent 时输出: ["preference_analysis"]
                            - 选择多个子Agent 并行时输出: ["preference_analysis", "attraction_filter"]
                            - 任务全部完成时输出: [] 或 ["FINISH"]

                            正常流程为顺序执行 preference_analysis → attraction_filter → route_arrangement → budget_estimation。
                            若预算估算超出用户预算 1.2 倍,回到 attraction_filter 重新筛选(最多重试 2 次)。
                            ## 硬性路由规则（F82）
                            行程规划、景点查询、预算/路线/住宿类请求，必须输出子 Agent 的 JSON 数组
                            （先 preference_analysis，如 ["preference_analysis","attraction_filter",...]）；
                            严禁直接输出 FINISH 或空数组。仅当请求为闲聊、画像查询、功能咨询等
                            非规划类问题时才允许输出 FINISH。
                            合法元素仅限: preference_analysis、attraction_filter、route_arrangement、budget_estimation、FINISH。
                            """)
                    .instruction("用户的请求是: {input}")
                    .outputKey("final_output")
                    // F27：注册 token 用量采集拦截器（与 4 个子 Agent 共用同一实例，
                    // 按请求 ID 累加 totalTokens，未 begin() 的流程自动跳过）。
                    .interceptors(tokenUsageInterceptor)
                    // F26：关闭 mainAgent 子图的默认 MemorySaver。
                    // MainAgentNodeAction 用常量 threadId 调用该子图；默认 saver 会导致
                    // 单次调用内每轮路由 checkpoint 与当前输入合并（消息重复累积），
                    // 且跨调用复用同一 thread 状态。空 SaverConfig 使子图每次从输入全新开始。
                    .compileConfig(CompileConfig.builder()
                            .saverConfig(new SaverConfig())
                            .build())
                    .build();

            this.supervisor = SupervisorAgent.builder()
                    .name("travel_planning_supervisor")
                    .model(chatModel)
                    .mainAgent(mainAgent)
                    .subAgents(List.of(
                            prefAgent.getAgent(),
                            attrAgent.getAgent(),
                            routeAgent.getAgent(),
                            budgetAgent.getAgent()
                    ))
                    // F26：父图保留默认 saver（满足子图适配器要求），仅限制循环迭代上限。
                    .compileConfig(CompileConfig.builder()
                            .recursionLimit(SUPERVISOR_RECURSION_LIMIT)
                            .build())
                    .build();

            log.info("TravelSupervisorAgent 初始化完成, 子Agent: preference_analysis, attraction_filter, route_arrangement, budget_estimation");
        } catch (Exception e) {
            log.error("TravelSupervisorAgent 初始化失败", e);
            throw new RuntimeException("Failed to build TravelSupervisorAgent: " + e.getMessage(), e);
        }
    }

    /**
     * 执行行程规划
     *
     * <p>将用户输入传入 SupervisorAgent，由 LLM 调度子 Agent 完成规划。</p>
     *
     * @param userInput 用户自然语言输入（如"帮我规划北京三日游，预算5000元"）
     * @return 行程规划结果（JSON 字符串，需由调用方解析）
     */
    public String executePlanning(String userInput) throws Exception {
        return executePlanningWithUsage(userInput, null).answer();
    }

    /**
     * F27：执行行程规划并返回回答与真实 token 消耗。
     *
     * <p>token 为本次 SupervisorAgent 全部 LLM 调用（路由 + 4 个子 Agent）的
     * {@code totalTokens} 之和，经 {@link TokenUsageInterceptor} 按请求 ID 采集。
     * F64/B2：userId 写入 RunnableConfig.metadata，供画像工具（get_user_profile /
     * save_user_profile）从 ToolContext 读取，不依赖 LLM 传参。</p>
     */
    public PlanningResult executePlanningWithUsage(String userInput, Long userId) throws Exception {
        log.info("开始执行行程规划: input={}, userId={}", userInput, userId);
        long start = System.currentTimeMillis();
        CompletableFuture<Optional<OverAllState>> future = null;
        String requestId = UUID.randomUUID().toString();
        tokenUsageInterceptor.begin(requestId);
        try {
            // F26 修复：必须执行 SupervisorAgent 整图（多步路由循环），
            // 而不是 supervisor.getMainAgent().call()——那只是路由 Agent，
            // 只会返回下一步子 Agent 名单（如 ["preference_analysis"]）。
            // graph.invoke 为阻塞调用且无超时参数，用 CompletableFuture 提供硬性时间边界。
            // F51：每次调用使用唯一 threadId，父图 checkpoint 按调用隔离。
            // 否则父图默认 MemorySaver + 默认 threadId 会跨调用复用子 Agent 输出
            // （TC-10 第三次只跑 preference_analysis、直接沿用上一轮 attractions/routePlan/
            // budgetEstimate，回答陈旧且与上轮完全相同）。多轮上下文由 Phase A 显式历史注入承担。
            RunnableConfig.Builder configBuilder = RunnableConfig.builder()
                    .threadId("rag_" + requestId)
                    .addMetadata(TokenUsageInterceptor.REQUEST_ID_KEY, requestId);
            // F64/B2：聊天链画像工具 userId 透传（metadata → ToolContext）
            if (userId != null) {
                configBuilder.addMetadata(ProfileToolProvider.USER_ID_METADATA_KEY, userId);
            }
            RunnableConfig config = configBuilder.build();
            future = CompletableFuture.supplyAsync(
                    () -> invokeSupervisorSafely(supervisor, userInput, config), SUPERVISOR_EXECUTOR);
            OverAllState finalState = future.orTimeout(MAX_EXECUTION_SECONDS, TimeUnit.SECONDS)
                    .get()
                    .orElseThrow(() -> new IllegalStateException("Supervisor 未返回最终状态"));
            String result = buildFinalResponse(finalState);
            long totalTokens = tokenUsageInterceptor.endAndGet(requestId);
            // F77/B4-2：路由截断/非 JSON 防护——四键全空且路由 FINISH、且疑似规划类请求时，
            // 用新 requestId 重试一次整图（F63 "playplay" 类问题：解析失败被框架当 FINISH，
            // 预算/规划被跳过）；仍空才走 F66 直答兜底。
            // F85：回顾类请求（"上次/之前安排了哪些景点"）是合法 FINISH，跳过整图重试直接直答，
            // 避免白耗一次完整图执行（F82 两档之外的第三类，见 F85 3.5 边界说明）。
            if (!hasSectionOutput(finalState)
                    && looksLikePlanningRequest(userInput)
                    && !isRecallQuery(userInput)) {
                String retryRequestId = UUID.randomUUID().toString();
                tokenUsageInterceptor.begin(retryRequestId);
                RunnableConfig.Builder retryBuilder = RunnableConfig.builder()
                        .threadId("rag_" + retryRequestId)
                        .addMetadata(TokenUsageInterceptor.REQUEST_ID_KEY, retryRequestId);
                if (userId != null) {
                    retryBuilder.addMetadata(ProfileToolProvider.USER_ID_METADATA_KEY, userId);
                }
                try {
                    CompletableFuture<Optional<OverAllState>> retryFuture = CompletableFuture.supplyAsync(
                            () -> invokeSupervisorSafely(supervisor, userInput, retryBuilder.build()),
                            SUPERVISOR_EXECUTOR);
                    Optional<OverAllState> retried =
                            retryFuture.orTimeout(MAX_EXECUTION_SECONDS, TimeUnit.SECONDS).get();
                    if (retried.isPresent() && hasSectionOutput(retried.get())) {
                        finalState = retried.get();
                        result = buildFinalResponse(finalState);
                        totalTokens += tokenUsageInterceptor.endAndGet(retryRequestId);
                        log.info("路由重试成功: 重新生成规划, resultLength={}, tokens累计={}",
                                result != null ? result.length() : 0, totalTokens);
                    } else {
                        tokenUsageInterceptor.endAndGet(retryRequestId);
                        log.warn("路由重试仍未产生子 Agent 输出，保留原结果");
                    }
                } catch (Exception e) {
                    tokenUsageInterceptor.endAndGet(retryRequestId);
                    log.warn("路由重试失败，保留原结果: {}", e.getMessage());
                }
            }
            // F66：非规划类问题（画像查询/闲聊等）路由直接 FINISH 且无子 Agent 输出时，
            // 用主模型基于组合输入直答，避免返回兜底"抱歉，未能生成行程规划"。
            // （20:18 实测"我的旅行画像里有什么？"→ supervisor_next=[FINISH]，preference/attractions
            //  /routePlan/budgetEstimate 全为 0，B2 画像问答不可达。）
            if (!hasSectionOutput(finalState)) {
                try {
                    // F85：直答兜底从裸输入升级为"system 指令 + 输入"双消息——
                    // 回顾类用回顾专用指令（不得编造景点），其余用覆盖优先级指令
                    // （会话 feedback/最新确认 > constraint > 画像）。
                    String system;
                    if (isRecallQuery(userInput)) {
                        system = """
                                你是行程回顾助手。仅依据组合输入中【会话最新确认】【会话知识参考】的
                                itinerary_day 切片与最近对话回答；逐天列出景点；信息不足时明确说明
                                "未找到该行程记录"，不得编造景点。
                                """;
                    } else {
                        system = """
                                你正在基于组合输入回答用户的旅行问题。信息优先级：
                                1) 【会话最新确认】与【会话知识参考】中的 feedback/最新修正 > constraint > 用户画像；
                                2) 预算/目的地/天数等与画像不一致时，以会话内最近一次确认或修正为准；
                                3) 仅当输入无相关信息时才可基于常识作答。
                                """;
                    }
                    ChatResponse direct = chatModel.call(new Prompt(
                            List.of(new SystemMessage(system), new UserMessage(userInput))));
                    String text = direct.getResult() != null && direct.getResult().getOutput() != null
                            ? direct.getResult().getOutput().getText() : null;
                    if (text != null && !text.isBlank()) {
                        result = text.trim();
                        if (direct.getMetadata() != null && direct.getMetadata().getUsage() != null) {
                            totalTokens += direct.getMetadata().getUsage().getTotalTokens();
                        }
                        log.info("非规划类问题直答兜底: inputLength={}, answerLength={}",
                                userInput.length(), result.length());
                    }
                } catch (Exception e) {
                    log.warn("非规划类问题直答兜底失败，保留原结果: {}", e.getMessage());
                }
            }
            long cost = System.currentTimeMillis() - start;
            log.info("行程规划完成, 耗时={}ms, 结果长度={}, supervisor_next={}, "
                            + "输出: preference={}, attractions={}, routePlan={}, budgetEstimate={}, tokens={}",
                    cost, result != null ? result.length() : 0,
                    finalState.value("supervisor_next").orElse("N/A"),
                    textLen(finalState, "preference"),
                    textLen(finalState, "attractions"),
                    textLen(finalState, "routePlan"),
                    textLen(finalState, "budgetEstimate"),
                    totalTokens);
            return new PlanningResult(result, totalTokens);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TimeoutException) {
                if (future != null) {
                    future.cancel(true);
                }
                log.error("行程规划超时（超过 {} 秒）", MAX_EXECUTION_SECONDS);
                throw new IllegalStateException(
                        "行程规划超时（超过 " + MAX_EXECUTION_SECONDS + " 秒），请稍后重试", e);
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("行程规划执行失败", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (future != null) {
                future.cancel(true);
            }
            throw new IllegalStateException("行程规划被中断", e);
        } finally {
            tokenUsageInterceptor.endAndGet(requestId);
        }
    }

    /** F27：行程规划结果（回答 + 本次真实 token 消耗）。 */
    public record PlanningResult(String answer, long totalTokens) {
    }

    // ==================== F85 第二步：入口直答 / 回顾管线 ====================

    /**
     * F85：PROFILE/CHAT/FUNCTIONAL 意图的入口直答（不触发 supervisor），
     * 使用覆盖优先级 system 指令（会话最新确认/feedback > constraint > 画像）。
     */
    public PlanningResult answerDirect(String userInput, Long userId) {
        String system = """
                你正在基于组合输入回答用户的旅行问题。信息优先级：
                1) 【会话最新确认】与【会话知识参考】中的 feedback/最新修正 > constraint > 用户画像；
                2) 预算/目的地/天数等与画像不一致时，以会话内最近一次确认或修正为准；
                3) 仅当输入无相关信息时才可基于常识作答。
                """;
        ChatResponse direct = chatModel.call(new Prompt(
                List.of(new SystemMessage(system), new UserMessage(userInput))));
        String text = direct.getResult() != null && direct.getResult().getOutput() != null
                ? direct.getResult().getOutput().getText() : "";
        long tokens = direct.getMetadata() != null && direct.getMetadata().getUsage() != null
                ? direct.getMetadata().getUsage().getTotalTokens() : 0;
        log.info("[DirectAnswer] 入口直答: inputLength={}, answerLength={}, tokens={}",
                userInput == null ? 0 : userInput.length(), text.length(), tokens);
        return new PlanningResult(
                text.isBlank() ? "抱歉，暂时无法回答，请稍后重试。" : text.trim(), tokens);
    }

    /**
     * F85：RECALL 意图的轻量回顾管线——itinerary_day 切片确定性骨架 + LLM 润色
     * （零编造、低 token）；无切片时确定性返回"未找到"，不调 LLM。
     */
    public PlanningResult answerRecall(String userInput, List<Map<String, Object>> sessionHits) {
        String skeleton = buildRecallSkeleton(sessionHits);
        String question = extractCurrentQuestion(userInput);
        if (skeleton.isBlank()) {
            String fallback = "未找到该行程记录，请确认您是否在本会话中生成过行程。";
            log.info("[Recall] 无行程切片，直接返回: {}", fallback);
            return new PlanningResult(fallback, 0);
        }
        String system = """
                你是行程回顾助手。以下骨架来自会话行程切片，请据此整理回答；
                不得增删景点；信息不足时说明"未找到该行程记录"。
                """;
        ChatResponse direct = chatModel.call(new Prompt(List.of(
                new SystemMessage(system),
                new UserMessage(skeleton + "\n\n用户问题：" + question))));
        String text = direct.getResult() != null && direct.getResult().getOutput() != null
                ? direct.getResult().getOutput().getText() : "";
        long tokens = direct.getMetadata() != null && direct.getMetadata().getUsage() != null
                ? direct.getMetadata().getUsage().getTotalTokens() : 0;
        log.info("[Recall] 回顾管线完成: skeletonLen={}, answerLen={}, tokens={}",
                skeleton.length(), text.length(), tokens);
        return new PlanningResult(text.isBlank() ? skeleton : text.trim(), tokens);
    }

    /** F85：从结构化切片提取 itinerary_day 骨架（已是最近一次行程过滤后的命中） */
    private static String buildRecallSkeleton(List<Map<String, Object>> sessionHits) {
        if (sessionHits == null || sessionHits.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> hit : sessionHits) {
            if (!"itinerary_day".equals(String.valueOf(hit.getOrDefault("type", "")))) {
                continue;
            }
            String content = String.valueOf(hit.getOrDefault("content", "")).trim();
            if (!content.isBlank()) {
                lines.add(content);
            }
        }
        return String.join("\n", lines);
    }

    private static String extractCurrentQuestion(String userInput) {
        if (userInput == null) {
            return "";
        }
        int idx = userInput.lastIndexOf("【当前问题】");
        if (idx >= 0) {
            return userInput.substring(idx + "【当前问题】".length()).trim();
        }
        return userInput.trim();
    }

    // ==================== F26 最终回答组装 ====================

    /**
     * 包装 {@code supervisor.invoke}：{@code GraphRunnerException} 为受检异常，
     * 无法直接在 CompletableFuture.supplyAsync 的 lambda 中抛出，统一转为
     * RuntimeException，由 executePlanning 的 ExecutionException 分支解包处理。
     */
    private static Optional<OverAllState> invokeSupervisorSafely(
            SupervisorAgent supervisor, String userInput, RunnableConfig config) {
        try {
            return supervisor.invoke(userInput, config);
        } catch (Exception e) {
            throw new RuntimeException("Supervisor 执行失败", e);
        }
    }

    /**
     * 将最终 state 中的子 Agent 输出组装为面向用户的行程规划回答。
     *
     * <p>顺序：偏好分析 → 推荐景点 → 每日行程 → 预算估算；缺失段落自动跳过；
     * 全部缺失时回退 messages 中最后一条非路由 AssistantMessage，
     * 仍为空则返回友好提示。</p>
     */
    static String buildFinalResponse(OverAllState state) {
        List<String> parts = new ArrayList<>();
        addSection(parts, "偏好分析", toText(state.value("preference")));
        addSection(parts, "推荐景点", toText(state.value("attractions")));
        addSection(parts, "每日行程", toText(state.value("routePlan")));
        addSection(parts, "预算估算", toText(state.value("budgetEstimate")));
        if (!parts.isEmpty()) {
            return String.join("\n\n", parts);
        }
        String fallback = lastMeaningfulMessage(state);
        return fallback.isBlank() ? "抱歉，未能生成行程规划，请稍后重试。" : fallback;
    }

    private static void addSection(List<String> parts, String title, String text) {
        if (text != null && !text.isBlank()) {
            String cleaned = stripCodeFence(text);
            String formatted = SupervisorResponseFormatter.format(title, cleaned);
            parts.add("【" + title + "】\n" + (formatted != null ? formatted : cleaned));
        }
    }

    /**
     * 安全提取 outputKey 文本：递归解包 {@link Optional}，兼容
     * {@link AssistantMessage} / {@link String}（复用 TravelWorkflowBuilder 已验证模式，
     * F23 D1 修复）。防御值为 Map/其他类型时退化 toString，避免下游强转崩坏。
     */
    private static String toText(Object value) {
        if (value == null) return "";
        if (value instanceof Optional<?> opt) return toText(opt.orElse(null));
        if (value instanceof String s) return s;
        if (value instanceof AssistantMessage am) return am.getText();
        // F84：框架内部 GraphResponse 对象（如某轮子 Agent 输出异常时被写入 state）
        // toString 为 "com.alibaba.cloud.ai.graph.GraphResponse@hex"，泄漏进用户回答。
        // 该对象无可读文本，直接置空跳过对应段落。
        if (value instanceof com.alibaba.cloud.ai.graph.GraphResponse) return "";
        return value.toString();
    }

    /** 去除 LLM 常见输出 ```json ... ``` Markdown 代码围栏。 */
    private static String stripCodeFence(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            int lastIdx = t.lastIndexOf("```");
            if (firstNl > 0 && lastIdx > firstNl) {
                t = t.substring(firstNl + 1, lastIdx).trim();
            }
        }
        return t;
    }

    /** 回退：取 messages 中最后一条非路由决策的 AssistantMessage。 */
    private static String lastMeaningfulMessage(OverAllState state) {
        Object messages = state.value("messages").orElse(null);
        if (messages instanceof List<?> list) {
            for (int i = list.size() - 1; i >= 0; i--) {
                Object m = list.get(i);
                if (!(m instanceof AssistantMessage am)) {
                    continue;
                }
                String text = am.getText();
                if (text == null || text.isBlank()) {
                    continue;
                }
                if (looksLikeRoutingDecision(text)) {
                    continue;
                }
                return text;
            }
        }
        return "";
    }

    /** 路由决策形如 ["agent"] / ["FINISH"] / [] / FINISH，不当作最终回答。 */
    private static boolean looksLikeRoutingDecision(String text) {
        String t = text.trim();
        if ("FINISH".equalsIgnoreCase(t) || "[]".equals(t)) {
            return true;
        }
        return t.startsWith("[") && t.endsWith("]");
    }

    private static int textLen(OverAllState state, String key) {
        String t = toText(state.value(key));
        return t != null ? t.length() : 0;
    }

    /** F66：四个子 Agent 输出键是否至少有一个非空（判断是否真正走了规划流程） */
    private static boolean hasSectionOutput(OverAllState state) {
        return !toText(state.value("preference")).isBlank()
                || !toText(state.value("attractions")).isBlank()
                || !toText(state.value("routePlan")).isBlank()
                || !toText(state.value("budgetEstimate")).isBlank();
    }

    /** F77/B4-2：疑似规划类请求（避免对画像查询/闲聊等非规划问题多花一次整图调用） */
    private static boolean looksLikePlanningRequest(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return false;
        }
        String q = userInput;
        int idx = q.lastIndexOf("【当前问题】");
        if (idx >= 0) {
            q = q.substring(idx);
        }
        return q.contains("规划") || q.contains("行程") || q.contains("推荐")
                || q.contains("景点") || q.contains("日游") || q.contains("预算")
                || q.contains("攻略") || q.contains("哪里");
    }

    /**
     * F85：回顾类问题判定（事实型"上次/之前发生了什么"）。
     * 变更型（优化/调整/重新规划）必须返回 false——那是规划请求，不能早退。
     */
    static boolean isRecallQuery(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return false;
        }
        String q = userInput;
        int idx = q.lastIndexOf("【当前问题】");
        if (idx >= 0) {
            q = q.substring(idx);
        }
        boolean recall = q.contains("上次") || q.contains("之前")
                || q.contains("回顾") || q.contains("安排了哪些")
                || q.contains("都去了") || q.contains("去过") || q.contains("行程记录");
        boolean change = q.contains("优化") || q.contains("调整") || q.contains("重新规划")
                || q.contains("改成") || q.contains("换") || q.contains("重新安排");
        return recall && !change;
    }

    /**
     * 获取 SupervisorAgent 实例（供高级场景使用）
     */
    public SupervisorAgent getSupervisor() {
        return supervisor;
    }

    /**
     * 获取偏好分析子 Agent
     */
    public ReactAgent getPreferenceAgent() {
        return prefAgent.getAgent();
    }

    /**
     * 获取景点筛选子 Agent
     */
    public ReactAgent getAttractionAgent() {
        return attrAgent.getAgent();
    }

    /**
     * 获取路线编排子 Agent
     */
    public ReactAgent getRouteAgent() {
        return routeAgent.getAgent();
    }

    /**
     * 获取预算估算子 Agent
     */
    public ReactAgent getBudgetAgent() {
        return budgetAgent.getAgent();
    }
}
