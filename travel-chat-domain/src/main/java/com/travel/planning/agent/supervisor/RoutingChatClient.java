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

    private RoutingChatClient() {
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
            if (array == null || array.equals(text == null ? null : text.trim())) {
                normalized.add(generation);
            } else {
                // 主代理路由输出无工具调用/媒体，重建仅文本消息即可；usage 等
                // ChatResponse 元数据通过 from(response) 保留
                normalized.add(new Generation(new AssistantMessage(array)));
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

    private static boolean isJsonArray(String candidate) {
        try {
            return JsonUtils.fromJson(candidate, List.class) instanceof List;
        } catch (Exception e) {
            return false;
        }
    }
}
