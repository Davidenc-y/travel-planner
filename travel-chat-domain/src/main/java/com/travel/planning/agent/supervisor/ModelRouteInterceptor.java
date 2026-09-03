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
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import reactor.core.publisher.Flux;

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
        // M8-9k：优先把请求级模型写入请求 options（随请求对象传递，与线程/懒执行无关）；
        // options 可能为 null（如无工具的主 Agent），失败也不阻断，由下方 ThreadLocal 通道兜底。
        applyModelToOptions(request, model);
        AtomicReference<String> routedRef = new AtomicReference<>();
        ModelResponse response = ModelRoutingContext.runWith(model, () -> {
            // 同步/阻塞路径：模型调用发生在 handler.call 内，runWith 直接覆盖
            ModelResponse r = handler.call(request);
            routedRef.set(ModelRoutingContext.routed());
            return r;
        });
        // M8-9k：图流 Reactor 流式路径的模型调用延迟到订阅时执行
        // （DefaultStreamResponseSpec.chatResponse() = Flux.deferContextual）；
        // runWith 在 Flux 创建后即清理 ThreadLocal，且 doOnSubscribe 回调晚于上游
        // 懒执行——必须用 Flux.defer 在每次订阅时、上游执行前注入 ThreadLocal，
        // doFinally 清理并补记实际路由模型（trace 不再失真）。
        if (response != null && response.getMessage() instanceof Flux<?> flux) {
            return new ModelResponse(Flux.defer(() -> {
                ModelRoutingContext.set(model);
                routedRef.set(null);
                return flux.doFinally(signal -> {
                    routedRef.set(ModelRoutingContext.routed());
                    ModelRoutingContext.clear();
                    recordRouted(request, routedRef.get());
                });
            }));
        }
        recordRouted(request, routedRef.get());
        return response;
    }

    /** 请求级模型写入 options（Spring AI 通用 ChatOptions.model，不绑定具体 Provider）。 */
    private static void applyModelToOptions(ModelRequest request, String model) {
        try {
            if (request.getOptions() instanceof DefaultToolCallingChatOptions mutable) {
                mutable.setModel(model);
            }
        } catch (Exception e) {
            log.warn("[ModelRoute] 请求 options 改写模型失败，依赖 ThreadLocal 通道: {}", e.getMessage());
        }
    }

    /** 消费 requestId 侧信道，把实际路由模型写入追溯。 */
    private void recordRouted(ModelRequest request, String routed) {
        if (routed == null || request == null || request.getContext() == null) {
            return;
        }
        Object requestIdValue = request.getContext().get(TokenUsageInterceptor.REQUEST_ID_KEY);
        if (requestIdValue instanceof String requestId) {
            modelRouteTracker.record(requestId, routed);
        }
    }
}
