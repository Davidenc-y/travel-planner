package com.travel.planning.agent.budget;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.travel.planning.agent.supervisor.TokenUsageInterceptor;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 预算估算 Agent
 *
 * <p>根据行程路线估算总费用，包括：</p>
 * <ul>
 *   <li>门票费用：各景点门票之和</li>
 *   <li>餐饮费用：每日三餐预估</li>
 *   <li>交通费用：景点间交通+往返大交通</li>
 *   <li>住宿费用：酒店价格 × 天数</li>
 *   <li>其他费用：纪念品、应急等</li>
 * </ul>
 *
 * <p>使用 qwen-turbo 轻量模型，快速完成数值计算。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Component
public class BudgetEstimationAgent {

    private final ChatModel lightModel;
    private final TokenUsageInterceptor tokenUsageInterceptor;
    private ReactAgent agent;

    public BudgetEstimationAgent(@Qualifier("lightModel") ChatModel lightModel,
                                 TokenUsageInterceptor tokenUsageInterceptor) {
        this.lightModel = lightModel;
        this.tokenUsageInterceptor = tokenUsageInterceptor;
    }

    @PostConstruct
    public void init() throws Exception {
        try {
            this.agent = ReactAgent.builder()
                    .name("budget_estimation")
                    .description("估算旅游行程总费用")
                    .model(lightModel)
                    .systemPrompt("你是旅游预算估算专家，擅长根据行程路线估算总费用。")
                    .instruction("""
                            根据行程路线估算总费用。

                            费用构成：
                            1. ticketCost: 门票费用（各景点门票之和）
                            2. mealCost: 餐饮费用（每日三餐：早餐20元/人，午餐80元/人，晚餐100元/人）
                            3. transportCost: 市内交通（地铁5元/次/人，打车约30元/次）
                            4. hotelCost: 住宿费用（经济型200-300/晚，舒适型400-600/晚，奢华型800+/晚）
                            5. otherCost: 其他费用（纪念品+应急，按总费用10%估算）

                            估算规则：
                            - 根据出行风格（ECONOMY/COMFORT/LUXURY）调整住宿标准
                            - 根据出行人数调整总费用
                            - 输出各项明细和总计

                            必须输出 JSON 格式，不要输出其他内容。
                            JSON 字段说明：ticketCost, mealCost, transportCost, hotelCost, otherCost, totalCost, perPersonCost, currency, notes
                            示例：ticketCost=200, mealCost=600, transportCost=150, hotelCost=1200, otherCost=215, totalCost=2365, perPersonCost=2365, currency=CNY, notes=住宿按舒适型400元/晚计算
                    """)
                    .outputKey("budgetEstimate")
                    // F27：注册 token 用量采集拦截器
                    .interceptors(tokenUsageInterceptor)
                    .build();
            log.info("BudgetEstimationAgent 初始化完成");
        } catch (Exception e) {
            log.error("BudgetEstimationAgent 初始化失败", e);
            throw new RuntimeException("Failed to build BudgetEstimationAgent: " + e.getMessage(), e);
        }
    }

    /**
     * 获取 ReactAgent 实例（供 StateGraph 节点调用）
     */
    public ReactAgent getAgent() {
        return agent;
    }
}
