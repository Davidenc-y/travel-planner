package com.travel.planning.agent.preference;

import com.travel.planning.agent.AbstractReactSubAgent;
import com.travel.planning.agent.supervisor.TokenUsageInterceptor;
import com.travel.planning.memory.longterm.ProfileToolProvider;
import com.travel.planning.prompt.PromptTemplates;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 偏好分析 Agent（M3-7：基于 AbstractReactSubAgent 模板，行为与 F64/F27 原实现一致）。
 */
@Slf4j
@Component
public class PreferenceAnalysisAgent extends AbstractReactSubAgent {

    private final ChatModel lightModel;
    private final TokenUsageInterceptor tokenUsageInterceptor;
    private final ProfileToolProvider profileToolProvider;
    private final PromptTemplates promptTemplates;

    public PreferenceAnalysisAgent(@Qualifier("lightModel") ChatModel lightModel,
                                   TokenUsageInterceptor tokenUsageInterceptor,
                                   ProfileToolProvider profileToolProvider,
                                   PromptTemplates promptTemplates) {
        this.lightModel = lightModel;
        this.tokenUsageInterceptor = tokenUsageInterceptor;
        this.profileToolProvider = profileToolProvider;
        this.promptTemplates = promptTemplates;
    }

    @Override
    protected ChatModel model() {
        return lightModel;
    }

    @Override
    protected String name() {
        return "preference_analysis";
    }

    @Override
    protected String description() {
        return "从用户输入中提取目的地、天数、预算、兴趣等结构化偏好数据";
    }

    @Override
    protected String systemPrompt() {
        return promptTemplates.agentPreferenceSystem();
    }

    @Override
    protected String instruction() {
        return promptTemplates.agentPreferenceInstruction();
    }

    @Override
    protected String outputKey() {
        return "preference";
    }

    @Override
    protected List<ToolCallback> tools() {
        return profileToolProvider.toolCallbacks();
    }

    @Override
    protected TokenUsageInterceptor interceptor() {
        return tokenUsageInterceptor;
    }
}
