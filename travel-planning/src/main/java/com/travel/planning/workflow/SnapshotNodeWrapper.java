package com.travel.planning.workflow;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.cloud.ai.graph.internal.node.Node;
import com.travel.common.util.AgentOutputUtils;
import com.travel.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 节点快照包装器（M4-8/P1-5，R2 G1：函数式装饰器，Node 级包装）。
 *
 * <p>四个关键产物节点是框架 {@code agent.asNode(true,false)} 生成的 {@link Node}
 * （id + ActionFactory），业务侧无代码切入点——本包装器在节点动作完成后异步落快照，
 * {@code whenComplete} 透传原始 Future（正常/异常路径行为完全不变）。</p>
 *
 * <p>payload 归一化复用 {@link AgentOutputUtils}（Optional 递归解包 → AssistantMessage
 * 取 text），再剥离代码围栏并经 Jackson 校验；框架对象 toString（GraphResponse@hex，
 * F84 教训）以 {@code com.alibaba.} 前缀守卫拒绝入库。</p>
 */
public final class SnapshotNodeWrapper {

    private SnapshotNodeWrapper() {
    }

    /** state 键：当前任务 id（t_itinerary GENERATING 占位行），由 initialState 注入 */
    public static final String TASK_ID_KEY = "taskId";

    public static Node wrap(String nodeName, Node agentNode, ItineraryTaskSnapshotPort snapshotPort) {
        return new Node(agentNode.id(), config -> {
            AsyncNodeActionWithConfig inner = agentNode.actionFactory().apply(config);
            return (state, cfg) -> {
                CompletableFuture<Map<String, Object>> future = inner.apply(state, cfg);
                future.whenComplete((output, err) -> {
                    if (err != null) {
                        return; // 节点失败不落快照（resume 不会把失败轮产物当断点）
                    }
                    try {
                        Long taskId = readTaskId(state);
                        String payload = normalize(output);
                        if (taskId != null && payload != null) {
                            snapshotPort.writeAsync(taskId, nodeName, payload);
                        }
                    } catch (Exception ignore) {
                        // 快照失败绝不影响节点主流程（兜底=resume 回退整图重跑）
                    }
                });
                return future;
            };
        });
    }

    static Long readTaskId(OverAllState state) {
        Object v = state == null ? null : state.value(TASK_ID_KEY).orElse(null);
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null ? null : Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 归一化节点输出（state update Map）为可入库的业务 JSON 文本：
     * 取首个非 null 值（outputKey→值）；剥离围栏 + Jackson 规范化；
     * 空/污染（框架对象 toString）返回 null（跳过快照）。
     */
    static String normalize(Map<String, Object> output) {
        if (output == null || output.isEmpty()) {
            return null;
        }
        Object value = null;
        for (Object v : output.values()) {
            if (v != null) {
                value = v;
                break;
            }
        }
        if (value == null) {
            return null;
        }
        // M6-51：防御——AgentLlmNode 流式分支输出为 Flux（toString 如 "FluxFlatMap"），
        // 不得入库污染快照（宁可无快照整图重跑，不可用坏快照续跑）
        if (value instanceof org.reactivestreams.Publisher) {
            return null;
        }
        return normalizeText(textOf(value));
    }

    /**
     * M6-51：从 state 值提取文本（Optional 递归解包 → AssistantMessage 取 text →
     * 其余 AgentOutputUtils.toText）。
     */
    static String textOf(Object value) {
        // asNode 输出即 Optional[AssistantMessage]（D1 实证）——common 的
        // AgentOutputUtils 只解包 Optional/String，AssistantMessage 必须在此显式解包
        // Optional 后取 text（否则 toString 污染快照）
        while (value instanceof Optional<?> opt) {
            value = opt.orElse(null);
        }
        if (value instanceof org.springframework.ai.chat.messages.AssistantMessage am) {
            return am.getText();
        }
        return AgentOutputUtils.toText(value);
    }

    /**
     * M6-51：业务文本 → 可入库快照 payload（剥围栏、拒绝框架对象泄漏、
     * JSON 规范化；空/污染返回 null）。
     */
    static String normalizeText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String cleaned = AgentOutputUtils.stripCodeFence(text).trim();
        if (cleaned.startsWith("com.alibaba.") || cleaned.startsWith("Flux")) {
            // 框架对象 toString 泄漏（GraphResponse 等）或 Reactor Flux toString
            // （如 "FluxFlatMap"）——不入库，宁可无快照整图重跑
            return null;
        }
        try {
            JsonNode node = JsonUtils.getMapper().readTree(cleaned);
            return node.toString(); // 规范化 JSON 文本
        } catch (Exception e) {
            return cleaned; // 非 JSON 文本原样保留
        }
    }
}
