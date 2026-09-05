package com.travel.planning.agent.supervisor;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.JumpTo;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * M13-3：子 Agent 派发去重 Hook（Agent 包装模式）。
 *
 * <p>在 BEFORE_AGENT 读取图 state：若本 Agent 的 outputKey 已有非空值，
 * 则写 {@code jump_to=end} 短路 ReactAgent 循环（零 LLM 调用）。
 * 预算/冲突重试环通过显式清空 outputKey 获得 forceRerun 语义，无需额外通道。</p>
 */
@Slf4j
@HookPositions({HookPosition.BEFORE_AGENT})
public class DedupSubAgentHook extends AgentHook {

    private final String outputKey;

    public DedupSubAgentHook(String outputKey) {
        this.outputKey = outputKey;
    }

    @Override
    public String getName() {
        return "m13_dispatch_dedup";
    }

    @Override
    public List<JumpTo> canJumpTo() {
        return List.of(JumpTo.end);
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(
            OverAllState state, RunnableConfig config) {
        if (!hasNonEmpty(state, outputKey)) {
            return CompletableFuture.completedFuture(Map.of());
        }
        log.info("[DispatchDedup] outputKey 已有值，短路子Agent: key={}", outputKey);
        Map<String, Object> result = new HashMap<>();
        result.put("jump_to", JumpTo.end.name());
        return CompletableFuture.completedFuture(result);
    }

    private static boolean hasNonEmpty(OverAllState state, String outputKey) {
        Object value = state.value(outputKey).orElse(null);
        if (value instanceof Optional<?> optional) {
            value = optional.orElse(null);
        }
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value);
        return text != null && !text.isBlank() && !"Optional.empty".equals(text);
    }
}
