package com.travel.planning.workflow;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.travel.common.util.JsonUtils;
import com.travel.planning.agent.attraction.AttractionFilterAgent;
import com.travel.planning.agent.budget.BudgetEstimationAgent;
import com.travel.planning.agent.preference.PreferenceAnalysisAgent;
import com.travel.planning.agent.route.RouteArrangementAgent;
import com.travel.planning.agent.support.AttractionGroundingChecker;
import com.travel.planning.config.ItineraryConflictCheckProperties;
import com.travel.planning.memory.knowledge.KnowledgeRetrievalService;
import com.travel.planning.workflow.validation.ItineraryConflictValidator;
import com.travel.planning.workflow.validation.BudgetJsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;

/**
 * 旅游行程规划工作流构建器
 *
 * <p>使用 Spring AI Alibaba Agent Framework 的 {@code agent.asNode(true, false)} 标准用法，
 * 将 4 个 ReactAgent 子 Agent 集成为 StateGraph 节点。子 Agent 通过 {@code includeContents=true}
 * 接收父图 messages 对话历史，完成 ReAct 推理与输出。</p>
 *
 * <p>F21 修复（回退 F20 错误修复）：</p>
 * <ul>
 *   <li>恢复 {@code agent.asNode(true, false)} 框架标准用法，删除 F20 自定义 LlmNode（裸调 ChatModel）</li>
 *   <li>修复 ClassCastException 真正根因：UserInputNode 的 {@code messages} 键放
 *       {@link UserMessage} 而非 String（AppendStrategy 期望 List&lt;Message&gt;，String 污染导致
 *       asNode 读取 messages 时强转失败）</li>
 *   <li>OptimizeNode / 条件边用 {@link #toText(Object)} 安全提取 outputKey
 *       （兼容 AssistantMessage / String）</li>
 *   <li>修复 RouteArrangementAgent / BudgetEstimationAgent 的 outputKey 命名
 *       （route_plan→routePlan，budget_estimate→budgetEstimate，与 state 键一致）</li>
 * </ul>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Component
public class TravelWorkflowBuilder {

    private static final int MAX_RETRY = 2;
    private static final double BUDGET_OVERRUN_RATIO = 1.2;

    /** M4-8（R2 G2）：resume 断点集合（线性依赖，缓存键空间封闭） */
    public static final String RESUME_FULL = "full";
    private static final List<String> RESUME_KEYS = List.of(
            RESUME_FULL, "preference_analysis", "attraction_filter",
            "route_arrangement", "budget_estimation", "itinerary_optimize");

    private final PreferenceAnalysisAgent prefAgent;
    private final AttractionFilterAgent attrAgent;
    private final RouteArrangementAgent routeAgent;
    private final BudgetEstimationAgent budgetAgent;
    private final KnowledgeRetrievalService knowledgeRetrievalService;
    // M4-8：关键产物节点快照（resume 断点依据）
    private final ItineraryTaskSnapshotPort snapshotPort;
    /** M8-3：时间冲突确定性校验器（规则链） */
    private final ItineraryConflictValidator conflictValidator;
    /** M8-3：冲突校验配置（enabled=false 回到现图） */
    private final ItineraryConflictCheckProperties conflictProperties;

    /**
     * M3-9 → M4-8：预编译缓存从单例升级为<b>前缀子图缓存</b>。
     * {@code full} 键构建代码与 M3-9 完全一致（正常路径零回归）；resume 断点子图
     * 跳过断点之前节点（R2 G2：不污染主图结构，替代"边条件跳过"方案）。
     */
    private final Map<String, CompiledGraph> graphCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Object graphLock = new Object();

    public TravelWorkflowBuilder(
            PreferenceAnalysisAgent prefAgent,
            AttractionFilterAgent attrAgent,
            RouteArrangementAgent routeAgent,
            BudgetEstimationAgent budgetAgent,
            KnowledgeRetrievalService knowledgeRetrievalService,
            ItineraryTaskSnapshotPort snapshotPort,
            ItineraryConflictValidator conflictValidator,
            ItineraryConflictCheckProperties conflictProperties) {
        this.prefAgent = prefAgent;
        this.attrAgent = attrAgent;
        this.routeAgent = routeAgent;
        this.budgetAgent = budgetAgent;
        this.knowledgeRetrievalService = knowledgeRetrievalService;
        this.snapshotPort = snapshotPort;
        this.conflictValidator = conflictValidator;
        this.conflictProperties = conflictProperties;
    }

    /** 正常全图（M3-9 语义不变） */
    public CompiledGraph buildWorkflow() throws Exception {
        return buildWorkflow(RESUME_FULL);
    }

    /**
     * M4-8：按断点构建/取缓存子图。
     *
     * @param resumeFrom 断点节点名（该节点起执行）：full / preference_analysis /
     *                   attraction_filter / route_arrangement / budget_estimation /
     *                   itinerary_optimize（非法值回退 full）
     */
    public CompiledGraph buildWorkflow(String resumeFrom) throws Exception {
        String key = RESUME_KEYS.contains(resumeFrom) ? resumeFrom : RESUME_FULL;
        CompiledGraph g = graphCache.get(key);
        if (g != null) {
            return g;
        }
        synchronized (graphLock) {
            g = graphCache.get(key);
            if (g == null) {
                try {
                    g = doBuild(key);
                } catch (Exception e) {
                    throw new RuntimeException("TravelWorkflow 构建失败: resumeFrom=" + key, e);
                }
                graphCache.put(key, g);
            }
            return g;
        }
    }

    private CompiledGraph doBuild(String resumeFrom) throws Exception {
        KeyStrategyFactory strategyFactory = () -> {
            Map<String, KeyStrategy> map = new HashMap<>();
            // messages 用 AppendStrategy —— 必须存 Message/List<Message>，不可存 String
            map.put("messages", new AppendStrategy());
            map.put("userInput", new ReplaceStrategy());
            map.put("preference", new ReplaceStrategy());
            map.put("attractions", new ReplaceStrategy());
            map.put("routePlan", new ReplaceStrategy());
            map.put("budgetEstimate", new ReplaceStrategy());
            map.put("itinerary", new ReplaceStrategy());
            map.put("mindmap", new ReplaceStrategy());
            map.put("retryCount", new ReplaceStrategy());
            map.put("routeRetryCount", new ReplaceStrategy());
            map.put("candidates", new ReplaceStrategy());
            map.put("conflictViolations", new ReplaceStrategy());
            map.put("userId", new ReplaceStrategy());
            map.put("retrievalQuery", new ReplaceStrategy());
            // M4-8：快照包装器读取任务 id
            map.put(SnapshotNodeWrapper.TASK_ID_KEY, new ReplaceStrategy());
            return map;
        };

        StateGraph workflow = new StateGraph(strategyFactory);

        // M4-8：断点包含集合——resumeFrom=X 表示 X 及之后执行，之前的跳过
        boolean full = RESUME_FULL.equals(resumeFrom);
        boolean hasPreference = full || "preference_analysis".equals(resumeFrom);
        boolean hasAttraction = hasPreference || "attraction_filter".equals(resumeFrom);
        boolean hasRoute = hasAttraction || "route_arrangement".equals(resumeFrom);
        boolean hasBudget = hasRoute || "budget_estimation".equals(resumeFrom);
        // budget_retry 回跳目标 attraction_filter 必须在子图内才保留重试语义；
        // 断点在 route/budget/optimize 时退化为直边（resume 模式的行为差异，见 M4-8 记录）
        boolean hasRetry = hasAttraction;
        // M8-3：冲突校验环仅在 route 段完整（route_arrangement + snapshot_route 均在子图内）
        // 且开关开启时挂载；断点在 route/budget 之后的子图无冲突重试（与 budget_retry 同语义）
        boolean hasConflict = hasRoute && conflictProperties.isEnabled();

        // 节点定义 — agent.asNode(true, false) 框架标准用法 + M6-51 快照状态节点
        //   includeContents=true:  传递父图 messages 给子 Agent（对话历史携带上下文）
        //   returnReasoningContents=false: 仅返回最终输出，不返回推理过程
        //   AgentSubGraphNode 的 action 输出为 Flux 旁路流（无法归一化），
        //   真实产物在执行后 state 中——由 agent 节点后的快照状态节点读取落库
        if (full) {
            workflow.addNode("user_input", AsyncNodeAction.node_async(new UserInputNode()));
            workflow.addNode("rag_retrieval", AsyncNodeAction.node_async(new RagRetrievalNode()));
        }
        if (hasPreference) {
            workflow.addNode("preference_analysis", prefAgent.getAgent().asNode(true, false));
            workflow.addNode("snapshot_preference",
                    SnapshotStateNode.action("preference_analysis", "preference", snapshotPort));
        }
        if (hasAttraction) {
            workflow.addNode("attraction_filter", attrAgent.getAgent().asNode(true, false));
            workflow.addNode("snapshot_attraction",
                    SnapshotStateNode.action("attraction_filter", "attractions", snapshotPort));
        }
        if (hasRoute) {
            workflow.addNode("route_arrangement", routeAgent.getAgent().asNode(true, false));
            workflow.addNode("snapshot_route",
                    SnapshotStateNode.action("route_arrangement", "routePlan", snapshotPort));
        }
        if (hasConflict) {
            workflow.addNode("conflict_check", AsyncNodeAction.node_async(new ConflictCheckNode()));
            workflow.addNode("conflict_retry", AsyncNodeAction.node_async(new RouteRetryCounterNode()));
        }
        if (hasBudget) {
            workflow.addNode("budget_estimation", budgetAgent.getAgent().asNode(true, false));
            workflow.addNode("snapshot_budget",
                    SnapshotStateNode.action("budget_estimation", "budgetEstimate", snapshotPort));
        }
        if (hasRetry) {
            workflow.addNode("budget_retry", AsyncNodeAction.node_async(new RetryCounterNode()));
        }
        workflow.addNode("itinerary_optimize", AsyncNodeAction.node_async(new OptimizeNode()));
        workflow.addNode("mindmap_output", AsyncNodeAction.node_async(new MindmapNode()));

        // 边定义：从子图起点接到 START
        String first = full ? "user_input"
                : hasPreference ? "preference_analysis"
                : hasAttraction ? "attraction_filter"
                : hasRoute ? "route_arrangement"
                : hasBudget ? "budget_estimation"
                : "itinerary_optimize";
        workflow.addEdge(START, first);
        if (full) {
            workflow.addEdge("user_input", "rag_retrieval");
            workflow.addEdge("rag_retrieval", "preference_analysis");
        }
        if (hasPreference) {
            workflow.addEdge("preference_analysis", "snapshot_preference");
            workflow.addEdge("snapshot_preference", "attraction_filter");
        }
        if (hasAttraction && hasRoute) {
            workflow.addEdge("attraction_filter", "snapshot_attraction");
            workflow.addEdge("snapshot_attraction", "route_arrangement");
        }
        if (hasRoute && hasBudget) {
            workflow.addEdge("route_arrangement", "snapshot_route");
            if (hasConflict) {
                // M8-3：快照后进入冲突校验节点；时间冲突 → conflict_retry 回退
                // route_arrangement（与 budget_retry→attraction_filter 同构）
                workflow.addEdge("snapshot_route", "conflict_check");
                workflow.addConditionalEdges(
                        "conflict_check",
                        AsyncEdgeAction.edge_async(this::conflictEdgeDecision),
                        Map.of("conflict_retry", "conflict_retry",
                                "budget_estimation", "budget_estimation"));
                workflow.addEdge("conflict_retry", "route_arrangement");
            } else {
                workflow.addEdge("snapshot_route", "budget_estimation");
            }
        }

        if (hasBudget) {
            // M6-51 二轮补边：agent 节点 → 快照状态节点（此前遗漏，导致
            // budget_estimation 无出边 → GraphRunnerException: edge ... doesn't exist）
            workflow.addEdge("budget_estimation", "snapshot_budget");
            // 条件边：预算超支（>1.2倍）且 retry<MAX_RETRY → budget_retry 递增后回退
            // attraction_filter 重新筛选（F23：回退必须经过计数节点防死循环）
            if (hasRetry) {
                workflow.addConditionalEdges(
                        "snapshot_budget",
                        AsyncEdgeAction.edge_async(state -> {
                            int retryCount = readRetryCount(state);
                            double estimatedCost = extractTotalCost(toText(state.value("budgetEstimate")));
                            double budget = parseBudgetFromPreference(toText(state.value("preference")));
                            boolean overBudget = estimatedCost > budget * BUDGET_OVERRUN_RATIO;
                            boolean canRetry = retryCount < MAX_RETRY;
                            log.info("预算判定: estimated={}, budget={}, ratio={}, retry={}/{}, overBudget={}, canRetry={}",
                                    estimatedCost, budget, BUDGET_OVERRUN_RATIO, retryCount, MAX_RETRY, overBudget, canRetry);
                            if (overBudget && canRetry) {
                                log.info("预算超支，进入重试计数节点 (retry {} -> {})", retryCount, retryCount + 1);
                                return "budget_retry";
                            }
                            log.info("预算正常或重试达上限，进入综合优化");
                            return "itinerary_optimize";
                        }),
                        Map.of("budget_retry", "budget_retry", "itinerary_optimize", "itinerary_optimize"));
                workflow.addEdge("budget_retry", "attraction_filter");
            } else {
                // M4-8：断点子图不含 attraction_filter → 超支也不回退（快照兜底语义）
                workflow.addEdge("snapshot_budget", "itinerary_optimize");
            }
        }
        workflow.addEdge("itinerary_optimize", "mindmap_output");
        workflow.addEdge("mindmap_output", END);

        int nodeCount = 2 + (full ? 2 : 0)
                + (hasPreference ? 2 : 0) + (hasAttraction ? 2 : 0)
                + (hasRoute ? 2 : 0) + (hasBudget ? 2 : 0) + (hasRetry ? 1 : 0)
                + (hasConflict ? 2 : 0);
        log.info("TravelWorkflow 构建完成: {} 节点, resumeFrom={}（快照状态节点×{}）",
                nodeCount, resumeFrom,
                (hasPreference ? 1 : 0) + (hasAttraction ? 1 : 0) + (hasRoute ? 1 : 0) + (hasBudget ? 1 : 0));
        // recursionLimit：M8-3 冲突环新增 2 节点。full 图正常路径 15 次；
        // 时间冲突重试每次额外 4 次（conflict_retry→route→snapshot→conflict_check），
        // 2 次重试 +8；预算重试每次额外 7 次，2 次 +14 → 上限 37。开关关闭时维持 30。
        int recursionLimit = hasConflict ? 40 : 30;
        return workflow.compile(CompileConfig.builder().recursionLimit(recursionLimit).build());
    }

    /**
     * M8-3：conflict_check 条件边——存在 ERROR 级违规且 routeRetryCount 未达上限
     * → conflict_retry；否则进入 budget_estimation。
     */
    private String conflictEdgeDecision(OverAllState state) {
        if (!conflictProperties.isEnabled()) {
            return "budget_estimation";
        }
        List<ItineraryConflictValidator.Violation> violations = safeViolations(conflictValidator.validate(
                toText(state.value("routePlan")), toText(state.value("candidates"))));
        int routeRetry = readRouteRetryCount(state);
        int maxRetry = Math.max(0, conflictProperties.getMaxRouteRetry());
        boolean hasError = ItineraryConflictValidator.hasError(violations);
        boolean canRetry = routeRetry < maxRetry;
        log.info("冲突判定: routeRetry={}/{}, violations={}, hasError={}, canRetry={}",
                routeRetry, maxRetry, violations.size(), hasError, canRetry);
        if (hasError && canRetry) {
            log.info("时间冲突，进入重试计数节点 (routeRetry {} -> {})", routeRetry, routeRetry + 1);
            return "conflict_retry";
        }
        return "budget_estimation";
    }

    // ==================== 工具方法 ====================

    /**
     * 安全提取 outputKey 的文本内容。
     *
     * <p>asNode 执行后 outputKey 可能存 {@link Optional} 包装的
     * {@link AssistantMessage}（框架内部行为，graph-core 的
     * {@code OverAllState.value(String)} 返回 Optional）或 String。
     * 本方法递归解包 Optional 并统一转为纯文本，避免下游强转崩溃或
     * "Optional[...]" 字符串污染 JSON（F23 D1 修复）。</p>
     */
    private static String toText(Object value) {
        if (value == null) return "";
        if (value instanceof Optional<?> opt) {
            return toText(opt.orElse(null));
        }
        if (value instanceof String s) return s;
        if (value instanceof AssistantMessage am) return am.getText();
        return value.toString();
    }

    /**
     * 将 Agent 输出文本转为可嵌入 JSON 的值：
     * 有效 JSON 解析为节点（对象/数组），非法 JSON（含 Markdown 代码围栏）先剥离围栏，
     * 仍失败则按 JSON 字符串转义保留，保证 itinerary 始终是合法 JSON（F23 D1 修复）。
     */
    private static Object toJsonValue(String text) {
        if (text == null || text.isBlank()) return null;
        String cleaned = stripCodeFence(text);
        try {
            return JsonUtils.getMapper().readTree(cleaned);
        } catch (Exception e) {
            return cleaned;
        }
    }

    /**
     * 剥离 LLM 常见输出的 ```json ... ``` Markdown 代码围栏。
     */
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

    /**
     * 从预算估算 JSON 中提取 totalCost 数值。
     * M8-3：委托 {@link BudgetJsonParser}（JsonUtils readTree + 异常兜底，取代字符串手工解析）。
     */
    private static double extractTotalCost(String budgetJson) {
        return BudgetJsonParser.extractTotalCost(budgetJson);
    }

    /**
     * 从偏好 JSON 中提取 budget 数值（用户预算上限）。
     * M8-3：委托 {@link BudgetJsonParser}。
     */
    private static double parseBudgetFromPreference(String preference) {
        return BudgetJsonParser.parseBudget(preference);
    }

    /**
     * 防御性读取 retryCount：兼容 Integer / Long / 其他 Number，避免类型污染导致
     * ClassCastException 静默打断重试计数（F24 补强，配合 RetryCounterNode 递增）。
     */
    @SuppressWarnings("unchecked")
    private static int readRetryCount(OverAllState state) {
        Object value = state.value("retryCount").orElse(0);
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }

    /** M8-3：防御性读取 routeRetryCount（时间冲突重试计数，独立于预算 retryCount） */
    private static int readRouteRetryCount(OverAllState state) {
        Object value = state.value("routeRetryCount").orElse(0);
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }

    // ==================== 节点实现 ====================

    /**
     * 用户输入节点 —— 将 userInput 包装为 UserMessage 放入 messages。
     *
     * <p>关键修复：messages 键必须放 {@link UserMessage}（Message 类型），
     * 不可放 String。AppendStrategy 维护 List&lt;Message&gt;，放 String 会污染列表，
     * 导致后续 asNode(includeContents=true) 读取 messages 时 String→Message 强转失败
     * （F18/F20 ClassCastException 的真正根因）。</p>
     */
    class UserInputNode implements NodeAction {
        @Override
        public Map<String, Object> apply(OverAllState state) throws Exception {
            String userInput = state.value("userInput", "").toString();
            Long userId = (Long) state.value("userId").orElse(0L);
            log.info("[Node:user_input] userId={}, inputLength={}", userId, userInput.length());
            Map<String, Object> result = new HashMap<>();
            // messages 放 UserMessage，不放 String
            result.put("messages", new UserMessage(userInput));
            result.put("retryCount", 0);
            return result;
        }
    }

    /**
     * F63：知识库预检索节点 —— 确定性调用 knowledge RAG，把候选景点注入 messages，
     * 保证行程链（TC-05）无论 LLM 是否调用工具都消费知识库。
     */
    class RagRetrievalNode implements NodeAction {
        @Override
        public Map<String, Object> apply(OverAllState state) throws Exception {
            // B3-6/F73：优先使用结构化 retrievalQuery（不含画像前缀），避免检索意图稀释
            // 与请求头超限；缺省回退 userInput。
            String retrievalQuery = state.value("retrievalQuery", "").toString();
            if (retrievalQuery == null || retrievalQuery.isBlank()) {
                retrievalQuery = state.value("userInput", "").toString();
            }
            String candidates = knowledgeRetrievalService.retrieveCandidates(retrievalQuery, 5);
            log.info("[Node:rag_retrieval] 预检索完成, 候选长度={}", candidates.length());
            Map<String, Object> result = new HashMap<>();
            if (candidates != null && !candidates.isBlank() && !"[]".equals(candidates)) {
                result.put("messages", new UserMessage("【知识库检索候选景点】\n" + candidates));
                // M8-3：结构化候选同步写 state（供 conflict_check 按名匹配开放时间/时长）；
                // 剥离 M8-2 数据来源说明段，只存 JSON 数组（state 内保持纯结构化）
                String candidatesJson = AttractionGroundingChecker.extractJsonArray(candidates);
                result.put("candidates", candidatesJson != null ? candidatesJson : candidates);
            }
            return result;
        }
    }

    /**
     * M8-3：冲突校验节点——规则链判定时间冲突，违规写 state 供日志/反馈消费；
     * 条件边重新校验并决定回退（与 budget 条件边同构，判定器为规则而非 LLM）。
     */
    class ConflictCheckNode implements NodeAction {
        @Override
        public Map<String, Object> apply(OverAllState state) {
            String routePlan = toText(state.value("routePlan"));
            String candidates = toText(state.value("candidates"));
            List<ItineraryConflictValidator.Violation> violations =
                    conflictValidator.validate(routePlan, candidates);
            log.info("[Node:conflict_check] 违规 {} 条: {}", violations.size(), violations);
            // M8-2 挂载（行程侧）：生成端引用校验（观测；无 TraceContext 时仅日志）
            recordGroundingToLog(candidates, routePlan);
            Map<String, Object> result = new HashMap<>();
            result.put("conflictViolations", JsonUtils.toJson(violations));
            return result;
        }

        private void recordGroundingToLog(String candidatesJson, String attractionsText) {
            try {
                // M8-7：routePlan 是嵌套 days 结构（name 在 attractions[].name），
                // 且最终回答可能被渲染成文本——统一用 checkText（递归 JSON + 文本行提取）
                var report = new AttractionGroundingChecker().checkText(
                        extractCandidateNames(candidatesJson), attractionsText);
                if (report.checked()) {
                    log.info("[Node:conflict_check] groundingRate={} ({}/{}), unmatched={}",
                            String.format("%.2f", report.rate()), report.matched(),
                            report.total(), report.unmatchedNames());
                }
            } catch (Exception e) {
                log.debug("[Node:conflict_check] grounding 观测失败（不阻断）: {}", e.getMessage());
            }
        }

        private static java.util.Set<String> extractCandidateNames(String candidatesJson) {
            String json = AttractionGroundingChecker.extractJsonArray(candidatesJson);
            if (json == null) {
                return java.util.Set.of();
            }
            try {
                List<?> list = JsonUtils.fromJson(json, List.class);
                java.util.Set<String> names = new java.util.LinkedHashSet<>();
                if (list != null) {
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> m && m.get("name") != null) {
                            names.add(String.valueOf(m.get("name")).trim());
                        }
                    }
                }
                return names;
            } catch (Exception e) {
                return java.util.Set.of();
            }
        }
    }

    /**
     * M8-3：时间冲突重试计数节点——routeRetryCount 递增 + 冲突反馈 SystemMessage
     * （与 RetryCounterNode/budget_retry 同构：计数防死循环 + 反馈防重试白跑）。
     */
    class RouteRetryCounterNode implements NodeAction {
        @Override
        public Map<String, Object> apply(OverAllState state) {
            int current = readRouteRetryCount(state);
            int next = current + 1;
            log.info("[Node:conflict_retry] routeRetryCount {} -> {}", current, next);
            Map<String, Object> result = new HashMap<>();
            result.put("routeRetryCount", next);

            List<ItineraryConflictValidator.Violation> violations = safeViolations(conflictValidator.validate(
                    toText(state.value("routePlan")), toText(state.value("candidates"))));
            if (!violations.isEmpty()) {
                StringBuilder feedback = new StringBuilder();
                feedback.append("【行程冲突反馈】上一版路线存在 ").append(violations.size())
                        .append(" 处冲突：\n");
                int idx = 1;
                for (ItineraryConflictValidator.Violation v : violations) {
                    feedback.append(idx++).append(". ").append(v.message()).append("；\n");
                }
                feedback.append("请重新编排：消除重叠、将 timeSlot 调整至各景点开放时间内，")
                        .append("并控制每日总时长在可用时间窗内。");
                log.info("[Node:conflict_retry] 追加冲突反馈: {}", feedback);
                result.put("messages", new SystemMessage(feedback.toString()));
            }
            return result;
        }
    }

    private static List<ItineraryConflictValidator.Violation> safeViolations(
            List<ItineraryConflictValidator.Violation> violations) {
        return violations == null ? List.of() : violations;
    }

    /**
     * 预算超支重试计数节点 —— 每次预算超支回退前将 retryCount 递增 1。
     *
     * <p>F23 修复：原实现条件边直接回退到 attraction_filter，没有任何节点递增
     * retryCount，导致 {@code retry=0/2} 恒成立、预算超支时死循环（用户实测
     * "预算超支，回退到景点筛选" 无限重复）。本节点配合条件边保证最多重试
     * {@link #MAX_RETRY} 次后进入综合优化，形成确定性退出边界。</p>
     */
    class RetryCounterNode implements NodeAction {
        @Override
        public Map<String, Object> apply(OverAllState state) {
            int current = readRetryCount(state);
            int next = current + 1;
            log.info("[Node:budget_retry] retryCount {} -> {}", current, next);
            Map<String, Object> result = new HashMap<>();
            result.put("retryCount", next);

            // F24 补强：重试时必须让下游 Agent 感知“预算超支多少、需如何降低成本”，
            // 否则 attraction_filter 会用同样的偏好与上下文重新筛选出相同景点，
            // 导致重试轮次全部白跑（用户实测 estimated=7026 三连相同即此现象）。
            double estimated = extractTotalCost(toText(state.value("budgetEstimate")));
            double budget = parseBudgetFromPreference(toText(state.value("preference")));
            if (budget != Double.MAX_VALUE && estimated > 0) {
                double overrun = estimated - budget;
                String feedback = "【预算重试反馈】上一版行程预算超支：预估总费用 %s 元，预算上限 %s 元，"
                        + "超出 %s 元。请在本轮重新筛选景点与编排路线时优先选择免费/低门票景点，"
                        + "并压缩住宿与交通档次，使新的预估总费用不超过预算上限。"
                        .formatted(formatMoney(estimated), formatMoney(budget), formatMoney(overrun));
                log.info("[Node:budget_retry] 追加预算反馈: overrun={}", formatMoney(overrun));
                result.put("messages", new SystemMessage(feedback));
            }
            return result;
        }
    }

    private static String formatMoney(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format("%.2f", v);
    }

    /**
     * 行程综合优化节点 —— 整合路线、预算、偏好为最终 itinerary。
     *
     * <p>用 {@link #toText(Object)} 安全提取 outputKey，兼容 AssistantMessage/String。</p>
     */
    class OptimizeNode implements NodeAction {
        @Override
        public Map<String, Object> apply(OverAllState state) throws Exception {
            String routePlan = toText(state.value("routePlan"));
            String budgetEstimate = toText(state.value("budgetEstimate"));
            String preference = toText(state.value("preference"));
            String candidates = toText(state.value("candidates"));
            log.info("[Node:itinerary_optimize] 整合路线+预算, routeLen={}, budgetLen={}",
                    routePlan.length(), budgetEstimate.length());
            // M8-3：费用一致性（WARNING 级，不触发重试；随日志观测）
            if (!candidates.isBlank()) {
                List<ItineraryConflictValidator.Violation> budgetWarnings =
                        conflictValidator.validateBudgetConsistency(
                                routePlan, candidates, budgetEstimate);
                if (budgetWarnings != null && !budgetWarnings.isEmpty()) {
                    log.warn("[Node:itinerary_optimize] 预算一致性警告: {}", budgetWarnings);
                }
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("routePlan", toJsonValue(routePlan));
            body.put("budgetEstimate", toJsonValue(budgetEstimate));
            body.put("preference", toJsonValue(preference));
            String itinerary = JsonUtils.getMapper().writeValueAsString(body);
            Map<String, Object> result = new HashMap<>();
            result.put("itinerary", itinerary);
            return result;
        }
    }

    /**
     * 思维导图输出节点 —— 生成结构化思维导图 JSON。
     */
    class MindmapNode implements NodeAction {
        @Override
        public Map<String, Object> apply(OverAllState state) throws Exception {
            String itinerary = toText(state.value("itinerary"));
            log.info("[Node:mindmap_output] 生成思维导图, itineraryLen={}", itinerary.length());
            // M3-2/P0-6：不再输出与真实行程无关的静态 JSON；
            // 由 ItineraryService 基于 itineraryJson 调用 MindmapGenerator 动态生成。
            Map<String, Object> result = new HashMap<>();
            result.put("mindmap", "");
            return result;
        }
    }
}
