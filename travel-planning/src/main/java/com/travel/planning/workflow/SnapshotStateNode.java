package com.travel.planning.workflow;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * M6-51：快照状态节点——从节点执行后的 OverAllState 读取真实业务产物并异步落快照。
 *
 * <p>背景：AgentSubGraphNode 的 action 输出 Map 中 outputKey 始终是
 * {@code Flux<GraphResponse<NodeOutput>>} 旁路流（toString 为 "FluxFlatMap"），
 * 无法直接归一化；真实产物只存在于子图执行后合并的 state（preference/attractions/
 * routePlan/budgetEstimate）。因此快照不再包装 agent 节点，改为在 agent 节点后
 * 插入本节点，从 state 取值写快照。</p>
 */
@Slf4j
public final class SnapshotStateNode {

    private SnapshotStateNode() {
    }

    /**
     * 构造快照节点 action。
     *
     * @param nodeName    快照键（与 resume 断点键一致，如 preference_analysis）
     * @param stateKey    state 中产物键（如 preference）
     * @param snapshotPort 快照端口
     */
    public static AsyncNodeAction action(String nodeName, String stateKey,
                                         ItineraryTaskSnapshotPort snapshotPort) {
        return AsyncNodeAction.node_async(state -> {
            try {
                Long taskId = SnapshotNodeWrapper.readTaskId(state);
                Object value = state == null ? null : state.value(stateKey).orElse(null);
                String payload = SnapshotNodeWrapper.normalizeText(
                        SnapshotNodeWrapper.textOf(value));
                if (taskId != null && payload != null) {
                    snapshotPort.writeAsync(taskId, nodeName, payload);
                }
            } catch (Exception e) {
                // 快照失败绝不影响主流程（兜底=resume 回退更早断点/整图重跑）
                log.warn("[SnapshotStateNode] 快照写入忽略异常: node={}, err={}",
                        nodeName, e.getMessage());
            }
            return Map.of(); // 不改 state
        });
    }
}
