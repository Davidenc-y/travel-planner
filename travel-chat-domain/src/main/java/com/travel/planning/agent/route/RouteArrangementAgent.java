package com.travel.planning.agent.route;

import com.travel.planning.agent.AbstractReactSubAgent;
import com.travel.planning.agent.supervisor.TokenUsageInterceptor;
import com.travel.planning.agent.supervisor.ModelRouteInterceptor;
import com.travel.planning.agent.supervisor.QuotaShortCircuitInterceptor;
import com.travel.planning.prompt.PromptTemplates;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 路线编排 Agent（M3-7：基于 AbstractReactSubAgent 模板，行为与原实现一致）。
 */
@Slf4j
@Component
public class RouteArrangementAgent extends AbstractReactSubAgent {

    private final ChatModel chatModel;
    private final TokenUsageInterceptor tokenUsageInterceptor;
    private final ModelRouteInterceptor modelRouteInterceptor;
    private final QuotaShortCircuitInterceptor quotaShortCircuitInterceptor;
    private final PromptTemplates promptTemplates;

    public RouteArrangementAgent(@Qualifier("chatModel") ChatModel chatModel,
                                 TokenUsageInterceptor tokenUsageInterceptor,
                                 ModelRouteInterceptor modelRouteInterceptor,
                                 QuotaShortCircuitInterceptor quotaShortCircuitInterceptor,
                                 PromptTemplates promptTemplates) {
        this.chatModel = chatModel;
        this.tokenUsageInterceptor = tokenUsageInterceptor;
        this.modelRouteInterceptor = modelRouteInterceptor;
        this.quotaShortCircuitInterceptor = quotaShortCircuitInterceptor;
        this.promptTemplates = promptTemplates;
    }

    @Override
    protected ChatModel model() {
        return chatModel;
    }

    @Override
    protected String name() {
        return "route_arrangement";
    }

    @Override
    protected String description() {
        return "编排每日行程路线和景点顺序";
    }

    @Override
    protected String systemPrompt() {
        return promptTemplates.agentRouteSystem();
    }

    @Override
    protected String instruction() {
        return promptTemplates.agentRouteInstruction();
    }

    @Override
    protected String outputKey() {
        return "routePlan";
    }

    @Override
    protected TokenUsageInterceptor interceptor() {
        return tokenUsageInterceptor;
    }

    @Override
    protected ModelRouteInterceptor modelRouteInterceptor() {
        return modelRouteInterceptor;
    }

    @Override
    protected QuotaShortCircuitInterceptor quotaShortCircuitInterceptor() {
        return quotaShortCircuitInterceptor;
    }
}
