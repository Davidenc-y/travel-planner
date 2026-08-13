package com.travel.planning.agent.supervisor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * F27：模型调用 token 用量采集拦截器。
 *
 * <p>注册在 SupervisorAgent 的 mainAgent 与 4 个子 Agent 上，位于
 * {@code AgentLlmNode} 非流式调用链外层。每次 LLM 调用的真实用量只存在于
 * {@code ChatResponse.getMetadata().getUsage()}（DashScope 实证），拦截器在此累加
 * {@code totalTokens}。</p>
 *
 * <p>按请求 ID 累加（{@link ConcurrentHashMap}）：请求 ID 经
 * {@code RunnableConfig} 的 metadata 传播（metadata 不可变且随子图配置复制，
 * graph-core 源码实证），不依赖线程局部性，支持并发请求隔离；未 begin() 的请求
 * （如行程工作流）自动跳过，互不干扰。</p>
 */
@Component
public class TokenUsageInterceptor extends ModelInterceptor {

    /** RunnableConfig metadata 中传递请求 ID 的键。 */
    public static final String REQUEST_ID_KEY = "travel_request_id";

    private final ConcurrentMap<String, Long> totals = new ConcurrentHashMap<>();

    @Override
    public String getName() {
        return "tokenUsageInterceptor";
    }

    /** 开启一次请求的 token 累计。 */
    public void begin(String requestId) {
        totals.put(requestId, 0L);
    }

    /** 结束请求并返回累计的 totalTokens；重复调用返回 0。 */
    public long endAndGet(String requestId) {
        Long value = totals.remove(requestId);
        return value != null ? value : 0L;
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        ModelResponse response = handler.call(request);
        if (response == null || response.getChatResponse() == null) {
            return response;
        }
        String requestId = request.getContext() == null
                ? null
                : (String) request.getContext().get(REQUEST_ID_KEY);
        if (requestId == null) {
            return response;
        }
        Usage usage = response.getChatResponse().getMetadata().getUsage();
        if (usage != null && usage.getTotalTokens() != null) {
            totals.computeIfPresent(requestId, (k, current) -> current + usage.getTotalTokens());
        }
        return response;
    }
}
