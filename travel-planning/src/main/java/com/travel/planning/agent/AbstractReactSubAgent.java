package com.travel.planning.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.travel.planning.agent.supervisor.TokenUsageInterceptor;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * M3-7：React 子 Agent 模板（消除 4 个子 Agent 的 init/getAgent 样板重复）。
 * 子类只需提供 name/model/description/systemPrompt/instruction/outputKey/tools/interceptor。
 */
@Slf4j
public abstract class AbstractReactSubAgent {

    private ReactAgent agent;

    @PostConstruct
    public final void init() {
        try {
            var builder = ReactAgent.builder()
                    .name(name())
                    .model(model())
                    .description(description())
                    .systemPrompt(systemPrompt())
                    .instruction(instruction())
                    .outputKey(outputKey());
            List<ToolCallback> tools = tools();
            if (tools != null && !tools.isEmpty()) {
                builder.tools(tools);
            }
            if (interceptor() != null) {
                builder.interceptors(interceptor());
            }
            this.agent = builder.build();
            log.info("{} 初始化完成", getClass().getSimpleName());
        } catch (Exception e) {
            log.error("{} 初始化失败", getClass().getSimpleName(), e);
            throw new RuntimeException("Failed to build " + getClass().getSimpleName()
                    + ": " + e.getMessage(), e);
        }
    }

    /** 供 StateGraph 节点调用 */
    public ReactAgent getAgent() {
        return agent;
    }

    protected abstract ChatModel model();

    protected abstract String name();

    protected abstract String description();

    protected abstract String systemPrompt();

    protected abstract String instruction();

    protected abstract String outputKey();

    protected List<ToolCallback> tools() {
        return List.of();
    }

    protected TokenUsageInterceptor interceptor() {
        return null;
    }
}
