package com.travel.planning.agent.supervisor;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.travel.common.util.AgentOutputUtils;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * M6-58/T9 Step4：Supervisor 最终回答组装与状态文本工具（从 TravelSupervisorAgent 迁出）。
 *
 * <p>纯静态、零状态；被阻塞/图流执行器与门面共用。行为与迁移前逐字节等价：
 * F23 D1 安全文本提取（Optional/AssistantMessage/String 解包）、
 * F84 GraphResponse 防泄漏置空、M3-2/P2-10 state key 分派排版、
 * M6-56/T5 代码围栏去除（AgentOutputUtils）均原样保留。</p>
 */
final class SupervisorResponseSupport {

    private SupervisorResponseSupport() {
    }

    /**
     * 将最终 state 中的子 Agent 输出组装为面向用户的行程规划回答。
     *
     * <p>顺序：偏好分析 → 推荐景点 → 每日行程 → 预算估算；缺失段落自动跳过；
     * 全部缺失时回退 messages 中最后一条非路由 AssistantMessage，
     * 仍为空则返回友好提示。</p>
     */
    static String buildFinalResponse(OverAllState state) {
        List<String> parts = new ArrayList<>();
        // M3-2/P2-10：排版按 state key 分派（不依赖中文标题）
        addSection(parts, "preference", "偏好分析", toText(state.value("preference")));
        addSection(parts, "attractions", "推荐景点", toText(state.value("attractions")));
        addSection(parts, "routePlan", "每日行程", toText(state.value("routePlan")));
        addSection(parts, "budgetEstimate", "预算估算", toText(state.value("budgetEstimate")));
        if (!parts.isEmpty()) {
            return String.join("\n\n", parts);
        }
        String fallback = lastMeaningfulMessage(state);
        return fallback.isBlank() ? "抱歉，未能生成行程规划，请稍后重试。" : fallback;
    }

    private static void addSection(List<String> parts, String key, String title, String text) {
        if (text != null && !text.isBlank()) {
            String cleaned = AgentOutputUtils.stripCodeFence(text);
            String formatted = SupervisorResponseFormatter.format(key, cleaned);
            parts.add("【" + title + "】\n" + (formatted != null ? formatted : cleaned));
        }
    }

    /**
     * 安全提取 outputKey 文本：递归解包 {@link Optional}，兼容
     * {@link AssistantMessage} / {@link String}（复用 TravelWorkflowBuilder 已验证模式，
     * F23 D1 修复）。防御值为 Map/其他类型时退化 toString，避免下游强转崩坏。
     */
    static String toText(Object value) {
        if (value == null) return "";
        if (value instanceof Optional<?> opt) return toText(opt.orElse(null));
        if (value instanceof String s) return s;
        if (value instanceof AssistantMessage am) return am.getText();
        // F84：框架内部 GraphResponse 对象（如某轮子 Agent 输出异常时被写入 state）
        // toString 为 "com.alibaba.cloud.ai.graph.GraphResponse@hex"，泄漏进用户回答。
        // 该对象无可读文本，直接置空跳过对应段落。
        if (value instanceof com.alibaba.cloud.ai.graph.GraphResponse) return "";
        return value.toString();
    }

    /** 回退：取 messages 中最后一条非路由决策的 AssistantMessage。 */
    private static String lastMeaningfulMessage(OverAllState state) {
        Object messages = state.value("messages").orElse(null);
        if (messages instanceof List<?> list) {
            for (int i = list.size() - 1; i >= 0; i--) {
                Object m = list.get(i);
                if (!(m instanceof AssistantMessage am)) {
                    continue;
                }
                String text = am.getText();
                if (text == null || text.isBlank()) {
                    continue;
                }
                if (PlanningHeuristics.looksLikeRoutingDecision(text)) {
                    continue;
                }
                return text;
            }
        }
        return "";
    }

    static int textLen(OverAllState state, String key) {
        String t = toText(state.value(key));
        return t != null ? t.length() : 0;
    }

    /** F66：四个子 Agent 输出键是否至少有一个非空（判断是否真正走了规划流程） */
    static boolean hasSectionOutput(OverAllState state) {
        return !toText(state.value("preference")).isBlank()
                || !toText(state.value("attractions")).isBlank()
                || !toText(state.value("routePlan")).isBlank()
                || !toText(state.value("budgetEstimate")).isBlank();
    }
}
