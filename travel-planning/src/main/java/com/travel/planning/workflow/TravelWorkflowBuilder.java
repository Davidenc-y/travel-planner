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
import com.travel.planning.agent.supervisor.TravelSupervisorAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;

/**
 * 旅游行程规划工作流构建器
 *
 * <p>7 节点 StateGraph 工作流（适配 Spring AI Alibaba 1.1.2.0 API）：</p>
 *
 * <pre>
 * START → user_input → preference_analysis → attraction_filter → route_arrangement
 *       → budget_estimation → [条件分支]
 *           ├── 预算超支 & retry<2 → attraction_filter (回退重试)
 *           └── 预算正常或重试达上限 → itinerary_optimize → mindmap_output → END
 * </pre>
 *
 * <p>API 适配（与 interview-system ParseWorkflowBuilder 对齐）：</p>
 * <ul>
 *   <li>KeyStrategyFactory（函数式接口）</li>
 *   <li>AsyncNodeAction.node_async() 静态方法</li>
 *   <li>AsyncEdgeAction.edge_async() 静态方法</li>
 *   <li>agent.asNode(true, false) 将 Agent 作为节点</li>
 * </ul>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Component
public class TravelWorkflowBuilder {

    /** 最大重试次数（预算超支回退） */
    private static final int MAX_RETRY = 2;

    /** 预算超支阈值（估算费用 > 预算 × 1.2 视为超支） */
    private static final double BUDGET_OVERRUN_RATIO = 1.2;

    private final TravelSupervisorAgent supervisorAgent;

    public TravelWorkflowBuilder(TravelSupervisorAgent supervisorAgent) {
        this.supervisorAgent = supervisorAgent;
    }

    /**
     * 构建旅游行程规划工作流
     *
     * @return 编译后的 CompiledGraph，可调用 .invoke(initialState) 执行
     */
    public CompiledGraph buildWorkflow() throws Exception {
        // === 状态策略工厂 ===
        KeyStrategyFactory strategyFactory = () -> {
            Map<String, KeyStrategy> map = new HashMap<>();
            map.put("messages", new AppendStrategy());          // 消息累积
            map.put("userInput", new ReplaceStrategy());         // 用户原始输入
            map.put("preference", new ReplaceStrategy());        // 偏好分析结果
            map.put("attractions", new ReplaceStrategy());       // 筛选景点列表
            map.put("routePlan", new ReplaceStrategy());         // 路线编排结果
            map.put("budgetEstimate", new ReplaceStrategy());    // 预算估算结果
            map.put("itinerary", new ReplaceStrategy());         // 最终行程
            map.put("mindmap", new ReplaceStrategy());           // 思维导图
            map.put("retryCount", new ReplaceStrategy());        // 重试计数
            map.put("userId", new ReplaceStrategy());            // 用户 ID
            return map;
        };

        StateGraph workflow = new StateGraph(strategyFactory);

        // === 节点定义 ===
        // 1. 用户输入解析（普通节点）
        workflow.addNode("user_input",
                AsyncNodeAction.node_async(new UserInputNode()));

        // 2. 偏好分析（Agent 节点）
        workflow.addNode("preference_analysis",
                supervisorAgent.getPreferenceAgent().asNode(true, false));

        // 3. 景点筛选（Agent 节点）
        workflow.addNode("attraction_filter",
                supervisorAgent.getAttractionAgent().asNode(true, false));

        // 4. 路线编排（Agent 节点）
        workflow.addNode("route_arrangement",
                supervisorAgent.getRouteAgent().asNode(true, false));

        // 5. 预算估算（Agent 节点）
        workflow.addNode("budget_estimation",
                supervisorAgent.getBudgetAgent().asNode(true, false));

        // 6. 综合优化（普通节点）
        workflow.addNode("itinerary_optimize",
                AsyncNodeAction.node_async(new OptimizeNode()));

        // 7. 思维导图生成（普通节点）
        workflow.addNode("mindmap_output",
                AsyncNodeAction.node_async(new MindmapNode()));

        // === 边定义 ===
        workflow.addEdge(START, "user_input");
        workflow.addEdge("user_input", "preference_analysis");
        workflow.addEdge("preference_analysis", "attraction_filter");
        workflow.addEdge("attraction_filter", "route_arrangement");
        workflow.addEdge("route_arrangement", "budget_estimation");

        // 条件分支：预算估算后判断是否超支
        workflow.addConditionalEdges(
                "budget_estimation",
                AsyncEdgeAction.edge_async(state -> {
                    int retryCount = (int) state.value("retryCount").orElse(0);
                    double estimatedCost = parseDouble(state.value("budgetEstimate"), 0);
                    double budget = parseBudgetFromPreference(state.value("preference"));

                    boolean overBudget = estimatedCost > budget * BUDGET_OVERRUN_RATIO;
                    boolean canRetry = retryCount < MAX_RETRY;

                    log.info("预算判定: estimated={}, budget={}, ratio={}, retry={}/{}, overBudget={}, canRetry={}",
                            estimatedCost, budget, BUDGET_OVERRUN_RATIO,
                            retryCount, MAX_RETRY, overBudget, canRetry);

                    if (overBudget && canRetry) {
                        log.info("预算超支，回退到景点筛选重新筛选 (retry={})", retryCount + 1);
                        return "attraction_filter";
                    }
                    log.info("预算正常或重试达上限，进入综合优化");
                    return "itinerary_optimize";
                }),
                Map.of(
                        "attraction_filter", "attraction_filter",
                        "itinerary_optimize", "itinerary_optimize"
                ));

        workflow.addEdge("itinerary_optimize", "mindmap_output");
        workflow.addEdge("mindmap_output", END);

        log.info("TravelWorkflow 构建完成: 7 节点, 条件分支: budget_estimation → attraction_filter|itinerary_optimize");

        return workflow.compile(CompileConfig.builder().build());
    }

    // ==================== 工具方法 ====================

    /**
     * 安全解析 double
     */
    private double parseDouble(Object value, double defaultValue) {
        if (value == null) return defaultValue;
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 从偏好 JSON 中提取预算（简化实现，实际由 JsonUtils 解析）
     */
    private double parseBudgetFromPreference(Object preference) {
        if (preference == null) return Double.MAX_VALUE;
        String prefStr = preference.toString();
        // 简化：尝试从 JSON 中匹配 "budget": 数字
        int idx = prefStr.indexOf("\"budget\"");
        if (idx < 0) return Double.MAX_VALUE;
        int colonIdx = prefStr.indexOf(":", idx);
        if (colonIdx < 0) return Double.MAX_VALUE;
        String afterColon = prefStr.substring(colonIdx + 1).trim();
        // 移除 null 的情况
        if (afterColon.startsWith("null")) return Double.MAX_VALUE;
        try {
            return Double.parseDouble(afterColon.replaceAll("[^0-9.].*", ""));
        } catch (Exception e) {
            return Double.MAX_VALUE;
        }
    }

    // ==================== 节点实现 ====================

    /**
     * 用户输入解析节点
     *
     * <p>从 OverAllState 中提取用户输入，传递给后续偏好分析 Agent。</p>
     */
    class UserInputNode implements NodeAction {
        @Override
        public Map<String, Object> apply(OverAllState state) throws Exception {
            String userInput = state.value("userInput", "").toString();
            Long userId = (Long) state.value("userId").orElse(0L);

            log.info("[Node:user_input] userId={}, inputLength={}", userId, userInput.length());

            Map<String, Object> result = new HashMap<>();
            result.put("messages", userInput);
            result.put("retryCount", 0);
            return result;
        }
    }

    /**
     * 综合优化节点
     *
     * <p>整合路线编排和预算估算结果，生成最终行程 JSON。</p>
     */
    class OptimizeNode implements NodeAction {
        @Override
        public Map<String, Object> apply(OverAllState state) throws Exception {
            String routePlan = state.value("routePlan", "").toString();
            String budgetEstimate = state.value("budgetEstimate", "").toString();
            String preference = state.value("preference", "").toString();

            log.info("[Node:itinerary_optimize] 整合路线+预算, routeLen={}, budgetLen={}",
                    routePlan.length(), budgetEstimate.length());

            // 简化：直接合并为最终行程 JSON
            // 实际场景可调用 ChatModel 做最终优化
            String itinerary = String.format(
                    "{\"routePlan\":%s,\"budgetEstimate\":%s,\"preference\":%s}",
                    routePlan.isEmpty() ? "null" : routePlan,
                    budgetEstimate.isEmpty() ? "null" : budgetEstimate,
                    preference.isEmpty() ? "null" : preference);

            Map<String, Object> result = new HashMap<>();
            result.put("itinerary", itinerary);
            return result;
        }
    }

    /**
     * 思维导图生成节点
     *
     * <p>将最终行程转换为思维导图 JSON 结构。</p>
     */
    class MindmapNode implements NodeAction {
        @Override
        public Map<String, Object> apply(OverAllState state) throws Exception {
            String itinerary = state.value("itinerary", "").toString();
            String preference = state.value("preference", "").toString();

            log.info("[Node:mindmap_output] 生成思维导图, itineraryLen={}", itinerary.length());

            // 简化：生成思维导图骨架（实际场景由 MindmapGenerator 生成）
            String mindmap = String.format("""
                    {
                      "title": "旅行规划",
                      "sections": [
                        {"title": "行程安排", "items": ["第1天", "第2天", "第3天"]},
                        {"title": "预算规划", "items": ["交通", "住宿", "餐饮", "门票"]},
                        {"title": "注意事项", "items": ["天气", "证件", "紧急联系人"]}
                      ]
                    }
                    """);

            Map<String, Object> result = new HashMap<>();
            result.put("mindmap", mindmap);
            return result;
        }
    }
}
