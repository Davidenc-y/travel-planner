package com.travel.planning.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.travel.planning.agent.supervisor.ModelRouteInterceptor;
import com.travel.planning.agent.supervisor.QuotaShortCircuitInterceptor;
import com.travel.planning.agent.supervisor.TokenUsageInterceptor;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
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
            ModelInterceptor tokenInterceptor = interceptor();
            ModelInterceptor routeInterceptor = modelRouteInterceptor();
            QuotaShortCircuitInterceptor quotaInterceptor = quotaShortCircuitInterceptor();
            // M8-9m：quota 必须处于链最外层（最后加入）——先检查短路再发起模型调用，
            // 且能捕获同步异常/流式 onError 后置位，短路后续并发节点
            List<ModelInterceptor> interceptors = new ArrayList<>(3);
            if (tokenInterceptor != null) {
                interceptors.add(tokenInterceptor);
            }
            if (routeInterceptor != null) {
                interceptors.add(routeInterceptor);
            }
            if (quotaInterceptor != null) {
                interceptors.add(quotaInterceptor);
            }
            if (!interceptors.isEmpty()) {
                builder.interceptors(interceptors.toArray(new ModelInterceptor[0]));
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

    /** M7：图流模型路由拦截器（子类注入共享 Bean；null=不挂载）。 */
    protected ModelRouteInterceptor modelRouteInterceptor() {
        return null;
    }

    /** M8-9m：额度不足短路拦截器（子类注入共享 Bean；null=不挂载）。 */
    protected QuotaShortCircuitInterceptor quotaShortCircuitInterceptor() {
        return null;
    }
}
