package com.travel.planning.agent.supervisor;

import com.travel.common.util.JsonUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * M7-8：主代理路由输出规范化 ChatClient。
 *
 * <p>背景：qwen3.7-max 偶发在路由决策数组前输出散文说明，而框架
 * {@code MainAgentNodeAction.parseJsonArrayOfStrings} 要求整个助手消息文本就是
 * JSON 数组；解析失败会回退 FINISH，导致 4 个子 Agent 流程被整体跳过（用户拿到
 * 一段散文而非行程）。</p>
 *
 * <p>实现：在 ChatClient 出口（call/stream 的 chatResponse）把
 * “散文 + 末尾 JSON 数组”归一为“仅数组”；纯数组/无数组原样透传。
 * 采用动态代理逐层包装（ChatClient → RequestSpec → Call/StreamResponseSpec），
 * 不改框架类、不影响 ModelRouteInterceptor / TokenUsageInterceptor。</p>
 */
public final class RoutingChatClient {

    /**
     * M20-2：子 Agent 流水线规范序（preference → attraction → route → budget 存在
     * 数据依赖：route 消费 attraction 输出、budget 消费 route 输出）。多元素并行
     * 数组串行化时按此序保留"最靠前"的一个，而非 LLM 列举序。
     */
    private static final List<String> CANONICAL_ORDER = List.of(
            "preference_analysis", "attraction_filter", "route_arrangement", "budget_estimation");

    /** M20-2：串行化守卫开关（默认开；关闭恢复 M7-8 仅归一化行为）。 */
    private static final AtomicBoolean SERIALIZE_DISPATCH = new AtomicBoolean(true);

    private RoutingChatClient() {
    }

    /** M20-2：运行期开关（测试与回滚用；默认 true）。 */
    public static void setSerializeDispatch(boolean enabled) {
        SERIALIZE_DISPATCH.set(enabled);
    }

    /**
     * 用规范化客户端包装主模型 ChatClient（仅用于 Supervisor 主代理路由决策）。
     */
    public static ChatClient wrap(ChatModel chatModel) {
        ChatClient raw = ChatClient.builder(chatModel).build();
        return wrapClient(raw);
    }

    private static ChatClient wrapClient(ChatClient raw) {
        return (ChatClient) proxy(raw, ChatClient.class);
    }

    private static Object proxy(Object target, Class<?> iface) {
        InvocationHandler handler = (proxy, method, args) -> invoke(target, method, args);
        return Proxy.newProxyInstance(
                iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        if (args == null) {
            args = new Object[0];
        }
        Object result = method.invoke(target, args);
        Class<?> rt = method.getReturnType();
        // 流式/阻塞调用链上的中间规格全部重新代理，保证最终 chatResponse 出口被归一化
        if (ChatClient.class.isAssignableFrom(rt)
                || ChatClient.Builder.class.isAssignableFrom(rt)
                || ChatClient.ChatClientRequestSpec.class.isAssignableFrom(rt)
                || ChatClient.CallResponseSpec.class.isAssignableFrom(rt)
                || ChatClient.StreamResponseSpec.class.isAssignableFrom(rt)) {
            if (result != null) {
                return proxy(result, rt);
            }
        }
        if ("chatResponse".equals(method.getName())) {
            if (result instanceof ChatResponse response) {
                return normalize(response);
            }
            if (result instanceof Flux<?> flux) {
                return flux.map(item ->
                        item instanceof ChatResponse response ? normalize(response) : item);
            }
        }
        return result;
    }

    /**
     * 归一化单个 ChatResponse：把“散文 + 末尾 JSON 数组”替换为仅数组文本。
     * 无数组或本就是数组时原样返回（保留原对象引用，零开销）。
     */
    public static ChatResponse normalize(ChatResponse response) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return response;
        }
        boolean changed = false;
        List<Generation> normalized = new ArrayList<>(response.getResults().size());
        for (Generation generation : response.getResults()) {
            AssistantMessage output = generation.getOutput();
            if (output == null) {
                normalized.add(generation);
                continue;
            }
            String text = output.getText();
            String array = extractLastJsonArray(text);
            String finalText = array == null ? null : serializeDispatchArray(array);
            if (finalText == null || finalText.equals(text == null ? null : text.trim())) {
                normalized.add(generation);
            } else {
                // 主代理路由输出无工具调用/媒体，重建仅文本消息即可；usage 等
                // ChatResponse 元数据通过 from(response) 保留
                normalized.add(new Generation(new AssistantMessage(finalText)));
                changed = true;
            }
        }
        if (!changed) {
            return response;
        }
        return ChatResponse.builder().from(response).generations(normalized).build();
    }

    /**
     * 从文本中提取“最后一个合法 JSON 数组”；找不到返回 null。
     * 从最后一个 ']' 向前逐个尝试 '['，避免散文中的方括号干扰。
     */
    static String extractLastJsonArray(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        int end = text.lastIndexOf(']');
        if (end < 0) {
            return null;
        }
        for (int start = text.lastIndexOf('[', end);
             start >= 0;
             start = text.lastIndexOf('[', start - 1)) {
            String candidate = text.substring(start, end + 1);
            if (isJsonArray(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * M20-2：确定性派发串行化——多元素路由数组按流水线规范序保留一个。
     *
     * <p>依据：框架并行派发的输出合并存在缺陷（M9-3a 五点实证 + 2026-09-06 REFINE
     * 实证：并行轮之后连后续串行子 Agent 的输出也一并丢失，说明是分支状态谱系损坏
     * 而非并发写冲突——串行提交队列救不回"从未到达"的写入）。prompt 约束（M20-1A）
     * 是建议性的，LLM 可能违反；本守卫在<b>我们自己的代码层</b>（主代理出口代理）确定性
     * 执行串行化：多元素 → 保留规范序最靠前的一个，其余由图循环在下一跳自然补派
     * （主 Agent 观察到 state 后会继续路由剩余步骤）。</p>
     *
     * <p>纯 FINISH / 空数组 / 单元素 / 不可解析数组原样透传。</p>
     */
    static String serializeDispatchArray(String arrayJson) {
        if (!SERIALIZE_DISPATCH.get()) {
            return arrayJson;
        }
        List<?> items;
        try {
            items = JsonUtils.fromJson(arrayJson, List.class);
        } catch (Exception e) {
            return arrayJson; // 不可解析：保持 M7-8 归一化结果
        }
        if (items == null) {
            return arrayJson;
        }
        List<String> agents = new ArrayList<>();
        for (Object item : items) {
            if (item != null) {
                String name = String.valueOf(item).trim();
                if (!name.isEmpty() && !"FINISH".equalsIgnoreCase(name)) {
                    agents.add(name);
                }
            }
        }
        if (agents.size() <= 1) {
            return arrayJson; // 空数组/单元素/FINISH-only：无需串行化
        }
        String keep = agents.get(0);
        final String q = String.valueOf('"');  // 日志中的字面双引号
        int bestRank = Integer.MAX_VALUE;
        for (String name : agents) {
            int rank = CANONICAL_ORDER.indexOf(name);
            if (rank >= 0 && rank < bestRank) {
                bestRank = rank;
                keep = name;
            }
        }
        if (keep.equals(agents.get(0)) && agents.size() == 1) {
            return arrayJson;
        }
        System.getLogger(RoutingChatClient.class.getName()).log(System.Logger.Level.WARNING,
                "[RoutingGuard] 并行派发已确定性串行化: {0} -> [" + q + "{1}" + q + "]（框架并行合并缺陷防护, M20-2）",
                arrayJson, keep);
        return "[\"" + keep + "\"]";
    }

    private static boolean isJsonArray(String candidate) {
        try {
            return JsonUtils.fromJson(candidate, List.class) instanceof List;
        } catch (Exception e) {
            return false;
        }
    }
}
