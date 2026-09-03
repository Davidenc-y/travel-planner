package com.travel.planning.agent.supervisor;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.travel.core.guard.CircuitBreaker;
import com.travel.planning.agent.attraction.AttractionFilterAgent;
import com.travel.planning.agent.budget.BudgetEstimationAgent;
import com.travel.planning.agent.preference.PreferenceAnalysisAgent;
import com.travel.planning.agent.route.RouteArrangementAgent;
import com.travel.planning.prompt.PromptTemplates;
import com.travel.planning.service.TurnCancellation;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 旅游行程规划总协调器门面。
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
 * <p>M6-58/T9：本类已按方法组拆分（P1-1 上帝类收敛）——init/getter/编排门面
 * 保留于此；静态启发式判定在 {@link PlanningHeuristics}、响应式阻塞/取消协作在
 * {@link ReactiveBlockSupport}、直答/回顾管线在 {@link DirectAnswerExecutor}、
 * 阻塞整图在 {@link SupervisorGraphExecutor}、图级流式在
 * {@link SupervisorStreamExecutor}，行为逐字节等价。</p>
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
    // M7 Batch 2：图流模型路由拦截器（Level 2，metadata 通道）
    private final ModelRouteInterceptor modelRouteInterceptor;
    // M8-9m：额度不足短路拦截器（注册在链最外层）
    private final QuotaShortCircuitInterceptor quotaShortCircuitInterceptor;
    // M3-20：Prompt 模板外置（P1-17）
    private final PromptTemplates promptTemplates;
    // F91：熔断（LLM/Supervisor 调用保护）
    private final CircuitBreaker.Registry circuitBreakerRegistry;

    private final DirectAnswerExecutor directAnswerExecutor;
    private final SupervisorGraphExecutor graphExecutor;
    private final SupervisorStreamExecutor streamExecutor;

    private SupervisorAgent supervisor;

    /**
     * 整体执行超时（秒）：硬性退出边界，防止路由循环/LLM 卡死时请求无限悬挂
     * （F26，镜像 ItineraryService 的 F24 超时+取消模式）。
     * M6-58/T9：提升为 public，供执行器（DirectAnswerExecutor/SupervisorGraphExecutor/
     * SupervisorStreamExecutor）共用同一超时口径。
     */
    public static final long MAX_EXECUTION_SECONDS = 300;

    /**
     * 父图迭代上限：正常流程约 14 次节点执行，含预算回退约 25 次；
     * 50 次作为有界安全网（默认 100 在 LLM 调用下空转过久）。
     */
    private static final int SUPERVISOR_RECURSION_LIMIT = 50;

    /**
     * Supervisor 执行专用虚拟线程池（Java 21，daemon），配合
     * {@link java.util.concurrent.CompletableFuture#cancel(boolean)} 及时中断阻塞的
     * graph.invoke。M6-58/T9：包内可见，供 SupervisorGraphExecutor 使用。
     */
    static final ExecutorService SUPERVISOR_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    public TravelSupervisorAgent(ChatModel chatModel,
                                  PreferenceAnalysisAgent prefAgent,
                                  AttractionFilterAgent attrAgent,
                                  RouteArrangementAgent routeAgent,
                                  BudgetEstimationAgent budgetAgent,
                                  TokenUsageInterceptor tokenUsageInterceptor,
                                  ModelRouteInterceptor modelRouteInterceptor,
                                  QuotaShortCircuitInterceptor quotaShortCircuitInterceptor,
                                  QuotaTripwire quotaTripwire,
                                  CircuitBreaker.Registry circuitBreakerRegistry,
                                  PromptTemplates promptTemplates) {
        this.chatModel = chatModel;
        this.prefAgent = prefAgent;
        this.attrAgent = attrAgent;
        this.routeAgent = routeAgent;
        this.budgetAgent = budgetAgent;
        this.tokenUsageInterceptor = tokenUsageInterceptor;
        this.modelRouteInterceptor = modelRouteInterceptor;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.promptTemplates = promptTemplates;
        this.directAnswerExecutor =
                new DirectAnswerExecutor(chatModel, promptTemplates, circuitBreakerRegistry);
        this.graphExecutor = new SupervisorGraphExecutor(
                tokenUsageInterceptor, circuitBreakerRegistry, promptTemplates,
                directAnswerExecutor, quotaTripwire);
        this.streamExecutor = new SupervisorStreamExecutor(
                tokenUsageInterceptor, circuitBreakerRegistry, promptTemplates,
                directAnswerExecutor, quotaTripwire);
        this.quotaShortCircuitInterceptor = quotaShortCircuitInterceptor;
    }

    @PostConstruct
    public void init() throws Exception {
        try {
            // 创建 mainAgent（路由决策者）— Spring AI Alibaba 1.1.2.0 必须设置
            ReactAgent mainAgent = ReactAgent.builder()
                    .name("travel_supervisor_main")
                    // M7-8：主代理路由输出经 RoutingChatClient 归一化——模型偶发在
                    // 路由数组前输出散文时，框架 MainAgentNodeAction 整段解析会失败并
                    // 回退 FINISH，导致子 Agent 流程被跳过；出口层提取末尾数组可根治
                    .chatClient(RoutingChatClient.wrap(chatModel))
                    .description("旅游行程规划总协调器,负责路由决策")
                    .systemPrompt(promptTemplates.supervisorSystem())
                    .instruction("用户的请求是: {input}")
                    .outputKey("final_output")
                    // F27：注册 token 用量采集拦截器（与 4 个子 Agent 共用同一实例，
                    // 按请求 ID 累加 totalTokens，未 begin() 的流程自动跳过）。
                    // M8-9m：quota 短路拦截器必须在链最外层（最后注册），
                    // 先检查短路再发起模型调用，并捕获同步/流式 403 后置位
                    .interceptors(tokenUsageInterceptor, modelRouteInterceptor,
                            quotaShortCircuitInterceptor)
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
        return executePlanningWithUsage(userInput, null, null).answer();
    }

    /**
     * F27：执行行程规划并返回回答与真实 token 消耗。
     *
     * <p>token 为本次 SupervisorAgent 全部 LLM 调用（路由 + 4 个子 Agent）的
     * {@code totalTokens} 之和，经 {@link TokenUsageInterceptor} 按请求 ID 采集。
     * F64/B2：userId 写入 RunnableConfig.metadata，供画像工具（get_user_profile /
     * save_user_profile）从 ToolContext 读取，不依赖 LLM 传参。
     * M6-58/T9：实现已迁至 {@link SupervisorGraphExecutor}，本方法为门面委托。</p>
     */
    public PlanningResult executePlanningWithUsage(String userInput, Long userId,
                                                   TurnCancellation cancellation) throws Exception {
        return graphExecutor.executePlanningWithUsage(supervisor, userInput, userId, cancellation);
    }

    /**
     * F27：行程规划结果（回答 + 本次真实 token 消耗）。
     *
     * @param routePlanJson M8-9：最终 state 的 routePlan JSON（可为 null；
     *                      供会话知识 itinerary_day 切片写入，解锁 RECALL/REFINE retention）
     */
    public record PlanningResult(String answer, long totalTokens, String routePlanJson) {
        /** 兼容既有调用方（无 routePlanJson 场景） */
        public PlanningResult(String answer, long totalTokens) {
            this(answer, totalTokens, null);
        }
    }

    // ==================== F85 第二步：入口直答 / 回顾管线 ====================

    /**
     * F85：PROFILE/CHAT/FUNCTIONAL 意图的入口直答（不触发 supervisor），
     * 使用覆盖优先级 system 指令（会话最新确认/feedback > constraint > 画像）。
     * M6-58/T9：实现已迁至 {@link DirectAnswerExecutor}，本方法为门面委托。
     */
    public PlanningResult answerDirect(String userInput, Long userId) {
        return directAnswerExecutor.answerDirect(userInput, userId);
    }

    /**
     * M6：PROFILE/CHAT/FUNCTIONAL 意图的入口直答——真 token 流。
     *
     * <p>与 {@link #answerDirect} 同 prompt/同 system 指令，但通过
     * {@code chatModel.stream} 逐增量回调 {@code tokenSink}；token 用量取流末
     * 累计 Usage（F27 口径）。空结果兜底文案同样回调，保证前端最终可见完整回答。</p>
     */
    public PlanningResult answerDirectStream(String userInput, Long userId, Consumer<String> tokenSink,
                                             TurnCancellation cancellation) {
        return directAnswerExecutor.answerDirectStream(userInput, userId, tokenSink, cancellation);
    }

    /**
     * F85：RECALL 意图的轻量回顾管线——itinerary_day 切片确定性骨架 + LLM 润色
     * （零编造、低 token）；无切片时确定性返回"未找到"，不调 LLM。
     */
    public PlanningResult answerRecall(String userInput, List<Map<String, Object>> sessionHits) {
        return directAnswerExecutor.answerRecall(userInput, sessionHits);
    }

    /**
     * M6：RECALL 意图的轻量回顾管线——真 token 流。
     *
     * <p>无行程切片时确定性返回（同步回调一次完整文本）；有切片时走
     * {@code chatModel.stream} 逐增量回调。与 {@link #answerRecall} 语义一致。</p>
     */
    public PlanningResult answerRecallStream(String userInput, List<Map<String, Object>> sessionHits,
                                             Consumer<String> tokenSink,
                                             TurnCancellation cancellation) {
        return directAnswerExecutor.answerRecallStream(userInput, sessionHits, tokenSink, cancellation);
    }

    /**
     * M6-18：规划路径图级流式（默认由路由层关闭，开启前需 golden 验证）。
     *
     * <p>用 {@code supervisor.stream} 替代 {@code invoke}：每个 {@link com.alibaba.cloud.ai.graph.NodeOutput}
     * 携带当时 {@link OverAllState}，取最后一个节点状态作为最终状态；
     * 节点名经 nodeThinking 输出（thinking 事件），最终回答经 tokenSink 分块输出。
     * 任何异常/空状态由调用方（ChatRoutingStep）降级回阻塞路径。
     * M6-58/T9：实现已迁至 {@link SupervisorStreamExecutor}，本方法为门面委托。</p>
     */
    public StreamPlanningResult streamPlanningWithUsage(String userInput, Long userId,
                                                        BiConsumer<String, String> nodeThinking,
                                                        Consumer<String> tokenSink,
                                                        TurnCancellation cancellation) throws Exception {
        return streamExecutor.streamPlanningWithUsage(
                supervisor, userInput, userId, nodeThinking, tokenSink, cancellation);
    }

    /** M6-18：图流规划结果 */
    /** M8-9：同 {@link PlanningResult}，图流路径附带 routePlanJson */
    public record StreamPlanningResult(String answer, long totalTokens, boolean fallback,
                                       String routePlanJson) {
        public StreamPlanningResult(String answer, long totalTokens, boolean fallback) {
            this(answer, totalTokens, fallback, null);
        }
    }

    /** M6-58/T9：最终回答组装静态委托（实现已迁至 SupervisorResponseSupport，供同包测试复用）。 */
    static String buildFinalResponse(OverAllState state) {
        return SupervisorResponseSupport.buildFinalResponse(state);
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
