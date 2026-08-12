package com.travel.planning.agent.supervisor;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.travel.planning.agent.attraction.AttractionFilterAgent;
import com.travel.planning.agent.budget.BudgetEstimationAgent;
import com.travel.planning.agent.preference.PreferenceAnalysisAgent;
import com.travel.planning.agent.route.RouteArrangementAgent;
import com.travel.planning.config.AiModelConfig;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;

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

    private SupervisorAgent supervisor;

    public TravelSupervisorAgent(ChatModel chatModel,
                                  PreferenceAnalysisAgent prefAgent,
                                  AttractionFilterAgent attrAgent,
                                  RouteArrangementAgent routeAgent,
                                  BudgetEstimationAgent budgetAgent) {
        this.chatModel = chatModel;
        this.prefAgent = prefAgent;
        this.attrAgent = attrAgent;
        this.routeAgent = routeAgent;
        this.budgetAgent = budgetAgent;
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
                            合法元素仅限: preference_analysis、attraction_filter、route_arrangement、budget_estimation、FINISH。
                            """)
                    .instruction("用户的请求是: {input}")
                    .outputKey("final_output")
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
        log.info("开始执行行程规划: input={}", userInput);
        long start = System.currentTimeMillis();

        try {
            String result = supervisor.getMainAgent().call(userInput).getText();
            long cost = System.currentTimeMillis() - start;
            log.info("行程规划完成, 耗时={}ms, 结果长度={}", cost, result != null ? result.length() : 0);
            return result;
        } catch (Exception e) {
            log.error("行程规划失败", e);
            throw e;
        }
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
