package com.travel.planning.agent.supervisor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.travel.common.exception.BusinessException;
import com.travel.common.exception.ErrorCode;
import com.travel.planning.service.ModelQuotaExceptionSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * M8-9m：模型额度不足短路拦截器（请求级 Tripwire 的图流集成点）。
 *
 * <p>职责单一：只做“检查/置位/短路”，不参与模型路由（ModelRouteInterceptor）
 * 与 token 采集（TokenUsageInterceptor）。注册为拦截器链最外层
 * （{@code interceptors(token, route, quota)}），保证：</p>
 * <ul>
 *   <li>调用前：本请求已触发额度不足 → 直接抛 40303，不再发起 HTTP；</li>
 *   <li>调用后（同步异常 / 流式 Flux onError）：识别 403+quota 并置位，
 *     后续并发节点立即短路；</li>
 *   <li>非额度错误不置位（超时/5xx/429 等走既有重试/熔断语义，行为不变）。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public final class QuotaShortCircuitInterceptor extends ModelInterceptor {

    private final QuotaTripwire quotaTripwire;

    @Override
    public String getName() {
        return "quotaShortCircuitInterceptor";
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        String scope = scopeOf(request);
        if (scope != null && quotaTripwire.isTripped(scope)) {
            log.warn("[QuotaShortCircuit] 本轮已触发额度不足，短路后续模型调用: scope={}", scope);
            // 无 cause 的业务异常：ChatService 侧按 40303 统一升级为带模型名文案
            throw new BusinessException(ErrorCode.MODEL_QUOTA_EXCEEDED.code(),
                    ErrorCode.MODEL_QUOTA_EXCEEDED.message());
        }
        try {
            ModelResponse response = handler.call(request);
            // 图流流式路径：403 在 Flux 订阅/消费时以 onError 出现（非 handler.call 抛出）
            if (response != null && response.getMessage() instanceof Flux<?> flux) {
                return new ModelResponse(flux.doOnError(err -> tripIfQuota(scope, err)));
            }
            return response;
        } catch (Exception e) {
            tripIfQuota(scope, e);
            throw e;
        }
    }

    /** 命中额度不足才置位；其余异常原样放行（不改变既有失败语义）。 */
    private void tripIfQuota(String scope, Throwable e) {
        if (scope != null && ModelQuotaExceptionSupport.isModelQuotaExceeded(e)) {
            quotaTripwire.trip(scope);
            log.warn("[QuotaShortCircuit] 模型额度不足，本轮后续模型调用将短路: scope={}", scope);
        }
    }

    /**
     * 短路作用域：优先轮次 clientMessageId（图流重试/跨 requestId 复用同一轮次
     * 仍可命中），缺失时回退请求 requestId（非聊天链路/测试）。
     */
    private static String scopeOf(ModelRequest request) {
        if (request == null || request.getContext() == null) {
            return null;
        }
        Object key = request.getContext().get(TokenUsageInterceptor.TURN_CANCELLATION_KEY);
        if (key instanceof String s && !s.isBlank()) {
            return s;
        }
        Object rid = request.getContext().get(TokenUsageInterceptor.REQUEST_ID_KEY);
        return rid instanceof String s && !s.isBlank() ? s : null;
    }
}
