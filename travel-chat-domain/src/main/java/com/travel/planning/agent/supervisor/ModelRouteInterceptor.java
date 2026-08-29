package com.travel.planning.agent.supervisor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.travel.aigateway.route.ModelRoutingContext;
import com.travel.planning.trace.ModelRouteTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * M7 Batch 2（T13，Level 2）：图流路径的模型路由拦截器。
 *
 * <p>图流（Reactor 异步线程）ThreadLocal 不可靠，改为经 RunnableConfig.metadata
 * 传播模型 key（仿 TokenUsageInterceptor.REQUEST_ID_KEY 通道）：</p>
 * <ul>
 *   <li>SupervisorStreamExecutor 写入 {@link #MODEL_KEY}；</li>
 *   <li>本拦截器在模型调用线程用 {@link ModelRoutingContext#runWith} 包裹
 *     {@code handler.call(request)}（比改写 request.options 更安全——避免
 *     DashScope/OpenAI options 类型耦合，探针已证两路均可行）；</li>
 *   <li>调用后把实际路由模型经 {@link ModelRouteTracker} 记录（T14）。</li>
 * </ul>
 * 无 MODEL_KEY / 无 requestId 时直通，行为与迁移前逐字节等价。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelRouteInterceptor extends ModelInterceptor {

    /** RunnableConfig metadata 中传递模型 key 的键（仿 TURN_CANCELLATION_KEY）。 */
    public static final String MODEL_KEY = "TRAVEL_MODEL_KEY";

    private final ModelRouteTracker modelRouteTracker;

    @Override
    public String getName() {
        return "modelRouteInterceptor";
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        Map<String, Object> context = request.getContext();
        if (context == null) {
            return handler.call(request);
        }
        Object modelValue = context.get(MODEL_KEY);
        if (!(modelValue instanceof String model) || model.isBlank()) {
            return handler.call(request);
        }
        AtomicReference<String> routedRef = new AtomicReference<>();
        ModelResponse response = ModelRoutingContext.runWith(model, () -> {
            ModelResponse r = handler.call(request);
            // runWith 返回后 ROUTED 已被 finally 清理，必须在 lambda 内捕获
            routedRef.set(ModelRoutingContext.routed());
            return r;
        });
        String routed = routedRef.get();
        Object requestIdValue = context.get(TokenUsageInterceptor.REQUEST_ID_KEY);
        if (requestIdValue instanceof String requestId && routed != null) {
            modelRouteTracker.record(requestId, routed);
        }
        return response;
    }
}
