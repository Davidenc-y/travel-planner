package com.travel.planning.trace;

import com.travel.common.entity.AgentTrace;

import java.util.ArrayList;
import java.util.List;

/**
 * 请求级追溯上下文（F89）。
 *
 * <p>ThreadLocal 承载：当前追溯对象、requestId（与 TokenUsageInterceptor 共用）、
 * token 累计与调用路径。由 {@link TraceAspect} begin/clear，服务层与 Agent 层
 * 通过静态方法填充 user/session/endpoint/path/tokens。</p>
 */
public final class TraceContext {

    private static final ThreadLocal<Holder> HOLDER = new ThreadLocal<>();

    public static final class Holder {
        public final AgentTrace trace = new AgentTrace();
        public String requestId;
        public long promptTokens;
        public long completionTokens;
        public long totalTokens;
        public final List<String> path = new ArrayList<>();
        /** M8-2：生成端引用校验通过率（0~1；null=未校验） */
        public Double groundingRate;
        /** M8-2：未命中候选集的景点名 JSON 数组（null=未校验） */
        public String groundingUnmatched;
        /** M8-2：检索降级原因（knowledge_empty/knowledge_feign_fail；null=未降级） */
        public String degradedReason;
        /** M8-6：REFINE 保留性校验通过率（0~1；null=未校验） */
        public Double retentionRate;
        /** M8-6：静默丢失景点名 JSON 数组（null=未校验） */
        public String retentionLost;

        public void addPath(String node) {
            path.add(node);
            trace.setCallPath(com.travel.common.util.JsonUtils.toJson(path));
        }
    }

    private TraceContext() {
    }

    public static boolean active() {
        return HOLDER.get() != null;
    }

    public static Holder current() {
        return HOLDER.get();
    }

    public static Holder begin(String requestId) {
        Holder h = new Holder();
        h.requestId = requestId;
        h.trace.setRequestId(requestId);
        h.trace.setStartTime(java.time.LocalDateTime.now());
        h.trace.setStatus("RUNNING");
        HOLDER.set(h);
        return h;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
