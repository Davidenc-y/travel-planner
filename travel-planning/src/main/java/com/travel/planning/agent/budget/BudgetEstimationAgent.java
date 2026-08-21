package com.travel.planning.agent.budget;

import com.travel.planning.agent.AbstractReactSubAgent;
import com.travel.planning.agent.supervisor.TokenUsageInterceptor;
import com.travel.planning.prompt.PromptTemplates;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 预算估算 Agent（M3-7：基于 AbstractReactSubAgent 模板，行为与原实现一致）。
 */
@Slf4j
@Component
public class BudgetEstimationAgent extends AbstractReactSubAgent {

    private final ChatModel lightModel;
    private final TokenUsageInterceptor tokenUsageInterceptor;
    private final PromptTemplates promptTemplates;

    public BudgetEstimationAgent(@Qualifier("lightModel") ChatModel lightModel,
                                 TokenUsageInterceptor tokenUsageInterceptor,
                                 PromptTemplates promptTemplates) {
        this.lightModel = lightModel;
        this.tokenUsageInterceptor = tokenUsageInterceptor;
        this.promptTemplates = promptTemplates;
    }

    @Override
    protected ChatModel model() {
        return lightModel;
    }

    @Override
    protected String name() {
        return "budget_estimation";
    }

    @Override
    protected String description() {
        return "估算旅游行程总费用";
    }

    @Override
    protected String systemPrompt() {
        return promptTemplates.agentBudgetSystem();
    }

    @Override
    protected String instruction() {
        return promptTemplates.agentBudgetInstruction();
    }

    @Override
    protected String outputKey() {
        return "budgetEstimate";
    }

    @Override
    protected TokenUsageInterceptor interceptor() {
        return tokenUsageInterceptor;
    }
}
