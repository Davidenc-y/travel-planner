package com.travel.aigateway.route;

import java.util.function.Supplier;

/**
 * M7：请求级模型路由上下文（ThreadLocal）。
 *
 * <p>仅 main 角色消费（D3：light 辅助链路不跟随用户选择）。执行边界必须用
 * {@link #runWith} 包裹并在 finally 清理，防虚拟线程池化后 ThreadLocal 残留泄漏。
 * 阻塞路径（直答/JSON/行程）在任务体内包裹；图流 Reactor 路径由 Batch 2 的
 * ModelRouteInterceptor 经 RunnableConfig.metadata 兜底。</p>
 */
public final class ModelRoutingContext {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();
    /** 最近一次实际路由到的模型（供 trace 记录；随 runWith/clear 一并清理） */
    private static final ThreadLocal<String> ROUTED = new ThreadLocal<>();

    private ModelRoutingContext() {
    }

    public static void set(String modelKey) {
        HOLDER.set(modelKey);
    }

    public static String current() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
        ROUTED.remove();
    }

    public static <T> T runWith(String modelKey, Supplier<T> supplier) {
        set(modelKey);
        ROUTED.remove();
        try {
            return supplier.get();
        } finally {
            clear();
        }
    }

    public static void runWith(String modelKey, Runnable action) {
        runWith(modelKey, () -> {
            action.run();
            return null;
        });
    }

    /** RoleRoutingChatModel 路由完成后记录实际模型（同一线程内可读）。 */
    public static void recordRouted(String modelKey) {
        ROUTED.set(modelKey);
    }

    /** 最近一次实际路由模型（无则 null）。 */
    public static String routed() {
        return ROUTED.get();
    }
}
