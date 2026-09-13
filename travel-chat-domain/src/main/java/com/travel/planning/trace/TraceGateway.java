package com.travel.planning.trace;

import com.travel.aigateway.route.ModelRoutingContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * MI-4：trace 门面（R8.2 收编落地方：end / recordRoutedModel 两方法）。
 *
 * <p>委托既有组件：{@link AgentTraceCollector#end}（TraceAspect 三调用点经此收编）与
 * {@link ModelRouteTracker#record}（ChatService.recordRoutedModel 收编 + ModelRouteInterceptor
 * 图流路径记录经此收编）。落库/落日志选择逻辑不变（TraceStore 条件装配，见 R8 §3）。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Component
@RequiredArgsConstructor
public class TraceGateway {

    private final AgentTraceCollector collector;
    private final ModelRouteTracker modelRouteTracker;

    /** 委托 AgentTraceCollector.end（结束一次调用：填充结束时间/耗时/token/路径/状态并入队）。 */
    public void end(TraceContext.Holder holder, String status, String errorMsg) {
        collector.end(holder, status, errorMsg);
    }

    /** 委托 ModelRouteTracker.record（requestId 侧信道：写入实际路由模型）。 */
    public void record(String requestId, String routedModel) {
        modelRouteTracker.record(requestId, routedModel);
    }

    /** 收编 ChatService.recordRoutedModel：direct 路径实际路由模型写入追溯（守卫语义逐字迁移）。 */
    public void recordRoutedModel() {
        String routed = ModelRoutingContext.routed();
        if (routed == null || !TraceContext.active()) {
            return;
        }
        modelRouteTracker.record(TraceContext.current().requestId, routed);
    }
}
