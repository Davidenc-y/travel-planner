package com.travel.planning.agent.support;

import java.util.function.Supplier;

/**
 * T-1：请求级锚定目的地上下文（ThreadLocal）。
 *
 * <p>解决 REFINE 轮跨城市替换缺陷：LLM 组装 attraction_search 检索词时不带城市，
 * knowledge 全局检索把外地景点换入行程。锚定目的地经本上下文注入工具链做确定性
 * 前缀硬约束（软约束=工具 description 提示，T-1b 落地）。</p>
 *
 * <p>三段式合法论证（完全复刻 ai-gateway ModelRoutingContext 先例，符合 E-48 精神）：
 * 调用线程 {@link #runWith} 设值 → supervisor 执行器建 RunnableConfig 时同线程
 * {@link #routed()} 读取、写入 config metadata（"travel_destination"）→ metadata 经
 * ToolContext 跨线程传递到工具。ThreadLocal 仅存活于生产者调用线程，执行边界
 * finally 清理，防虚拟线程池化后残留泄漏。工具侧只加前缀不删词，开关
 * travel.chat.attraction-search.destination-prefix-enabled 一键回退旧行为。</p>
 */
public final class DestinationContext {

    /** T-1：RunnableConfig.metadata 目的地键（执行器写入，工具经 ToolContext 读取）。 */
    public static final String DESTINATION_METADATA_KEY = "travel_destination";

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private DestinationContext() {
    }

    public static void set(String destination) {
        HOLDER.set(destination);
    }

    /** 当前锚定目的地（无则 null；仅生产者调用线程可读）。 */
    public static String routed() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static <T> T runWith(String destination, Supplier<T> supplier) {
        set(destination);
        try {
            return supplier.get();
        } finally {
            clear();
        }
    }

    public static void runWith(String destination, Runnable action) {
        runWith(destination, () -> {
            action.run();
            return null;
        });
    }
}
