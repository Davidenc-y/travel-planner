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
