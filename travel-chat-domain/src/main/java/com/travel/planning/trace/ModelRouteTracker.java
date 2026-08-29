package com.travel.planning.trace;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * M7 Batch 2：实际路由模型追踪（requestId → model）。
 *
 * <p>图流路径由 {@code ModelRouteInterceptor} 在模型调用线程记录，direct 路径由
 * ChatService 包裹捕获；AgentTraceCollector.end 消费（take）后写入 t_agent_trace.model_name。
 * 与 TokenUsageInterceptor 同为 requestId 侧信道，不依赖线程局部性。</p>
 */
@Component
public class ModelRouteTracker {

    private final ConcurrentMap<String, String> routed = new ConcurrentHashMap<>();

    public void record(String requestId, String model) {
        if (requestId != null && model != null) {
            routed.put(requestId, model);
        }
    }

    /** 取出并清理（追溯结束时消费）。 */
    public String take(String requestId) {
        return requestId == null ? null : routed.remove(requestId);
    }
}
