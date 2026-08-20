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
import com.travel.planning.memory.knowledge.KnowledgeRetrievalService;
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

    private final PreferenceAnalysisAgent prefAgent;
    private final AttractionFilterAgent attrAgent;
    private final RouteArrangementAgent routeAgent;
    private final BudgetEstimationAgent budgetAgent;
    private final KnowledgeRetrievalService knowledgeRetrievalService;

    public TravelWorkflowBuilder(
            PreferenceAnalysisAgent prefAgent,
            AttractionFilterAgent attrAgent,
            RouteArrangementAgent routeAgent,
            BudgetEstimationAgent budgetAgent,
            KnowledgeRetrievalService knowledgeRetrievalService) {
        this.prefAgent = prefAgent;
        this.attrAgent = attrAgent;
        this.routeAgent = routeAgent;
        this.budgetAgent = budgetAgent;
        this.knowledgeRetrievalService = knowledgeRetrievalService;
    }

    public CompiledGraph buildWorkflow() throws Exception {
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
            map.put("userId", new ReplaceStrategy());
            map.put("retrievalQuery", new ReplaceStrategy());
            return map;
        };

        StateGraph workflow = new StateGraph(strategyFactory);

        // 节点定义 — agent.asNode(true, false) 框架标准用法
        //   includeContents=true:  传递父图 messages 给子 Agent（对话历史携带上下文）
        //   returnReasoningContents=false: 仅返回最终输出，不返回推理过程
        workflow.addNode("user_input", AsyncNodeAction.node_async(new UserInputNode()));
        workflow.addNode("rag_retrieval", AsyncNodeAction.node_async(new RagRetrievalNode()));
        workflow.addNode("preference_analysis", prefAgent.getAgent().asNode(true, false));
        workflow.addNode("attraction_filter", attrAgent.getAgent().asNode(true, false));
        workflow.addNode("route_arrangement", routeAgent.getAgent().asNode(true, false));
        workflow.addNode("budget_estimation", budgetAgent.getAgent().asNode(true, false));
        workflow.addNode("budget_retry", AsyncNodeAction.node_async(new RetryCounterNode()));
        workflow.addNode("itinerary_optimize", AsyncNodeAction.node_async(new OptimizeNode()));
        workflow.addNode("mindmap_output", AsyncNodeAction.node_async(new MindmapNode()));

        // 边定义
        workflow.addEdge(START, "user_input");
        workflow.addEdge("user_input", "rag_retrieval");
        workflow.addEdge("rag_retrieval", "preference_analysis");
        workflow.addEdge("preference_analysis", "attraction_filter");
        workflow.addEdge("attraction_filter", "route_arrangement");
        workflow.addEdge("route_arrangement", "budget_estimation");

        // 条件边：预算超支（>1.2倍）且 retry<MAX_RETRY → 先经 budget_retry 递增 retryCount，
        // 再回退到 attraction_filter 重新筛选（F23 修复：原实现直接回退导致 retryCount 永不递增 → 死循环）
        workflow.addConditionalEdges(
                "budget_estimation",
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
        workflow.addEdge("itinerary_optimize", "mindmap_output");
        workflow.addEdge("mindmap_output", END);

        log.info("TravelWorkflow 构建完成: 9 节点（agent.asNode 标准用法 + rag_retrieval 预检索 + budget_retry 重试计数）");
        // recursionLimit=20：正常路径（含 2 次预算重试）约 14 次节点执行，
        // 20 次为硬性循环上限，作为防死循环兜底（默认值 100 在 LLM 调用下耗时过长）
        return workflow.compile(CompileConfig.builder().recursionLimit(20).build());
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
     */
    private static double extractTotalCost(String budgetJson) {
        if (budgetJson == null || budgetJson.isBlank()) return 0;
        int idx = budgetJson.indexOf("\"totalCost\"");
        if (idx < 0) idx = budgetJson.indexOf("totalCost");
        if (idx < 0) return 0;
        int colonIdx = budgetJson.indexOf(":", idx);
        if (colonIdx < 0) return 0;
        String afterColon = budgetJson.substring(colonIdx + 1).trim();
        try {
            return Double.parseDouble(afterColon.replaceAll("[^0-9.].*", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 从偏好 JSON 中提取 budget 数值（用户预算上限）。
     */
    private static double parseBudgetFromPreference(String preference) {
        if (preference == null || preference.isBlank()) return Double.MAX_VALUE;
        int idx = preference.indexOf("\"budget\"");
        if (idx < 0) idx = preference.indexOf("budget");
        if (idx < 0) return Double.MAX_VALUE;
        int colonIdx = preference.indexOf(":", idx);
        if (colonIdx < 0) return Double.MAX_VALUE;
        String afterColon = preference.substring(colonIdx + 1).trim();
        if (afterColon.startsWith("null")) return Double.MAX_VALUE;
        try {
            return Double.parseDouble(afterColon.replaceAll("[^0-9.].*", ""));
        } catch (Exception e) {
            return Double.MAX_VALUE;
        }
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
            }
            return result;
        }
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
            log.info("[Node:itinerary_optimize] 整合路线+预算, routeLen={}, budgetLen={}",
                    routePlan.length(), budgetEstimate.length());
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
