package com.travel.planning.workflow;

import com.travel.common.util.JsonUtils;
import com.travel.planning.workflow.validation.BudgetJsonParser;
import com.travel.planning.workflow.validation.ItineraryConflictValidator;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;
import java.util.Optional;

/**
 * MI-2：工作流纯文本工具（七个 static 纯函数自 {@link TravelWorkflowBuilder} 逐字符迁出：
 * toText / toJsonValue / stripCodeFence / extractTotalCost / parseBudgetFromPreference /
 * formatMoney / appendBudgetWarnings——无 StateGraph/节点对象依赖，语义与文案零变更；
 * 同包供 Builder 直调，可独立单测）。
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
class WorkflowPromptComposer {

    /**
     * 安全提取 outputKey 的文本内容。
     *
     * <p>asNode 执行后 outputKey 可能存 {@link Optional} 包装的
     * {@link AssistantMessage}（框架内部行为，graph-core 的
     * {@code OverAllState.value(String)} 返回 Optional）或 String。
     * 本方法递归解包 Optional 并统一转为纯文本，避免下游强转崩溃或
     * "Optional[...]" 字符串污染 JSON（F23 D1 修复）。</p>
     */
    static String toText(Object value) {
        if (value == null) return "";
        if (value instanceof Optional<?> opt) {
            return toText(opt.orElse(null));
        }
        if (value instanceof String s) return s;
        if (value instanceof AssistantMessage am) return am.getText();
        return value.toString();
    }

    /**
     * 将 Agent 输出文本转为可嵌入 JSON 的值：
     * 有效 JSON 解析为节点（对象/数组），非法 JSON（含 Markdown 代码围栏）先剥离围栏，
     * 仍失败则按 JSON 字符串转义保留，保证 itinerary 始终是合法 JSON（F23 D1 修复）。
     */
    static Object toJsonValue(String text) {
        if (text == null || text.isBlank()) return null;
        String cleaned = stripCodeFence(text);
        try {
            return JsonUtils.getMapper().readTree(cleaned);
        } catch (Exception e) {
            return cleaned;
        }
    }

    /**
     * 剥离 LLM 常见输出的 ```json ... ``` Markdown 代码围栏。
     */
    static String stripCodeFence(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            int lastIdx = t.lastIndexOf("```");
            if (firstNl > 0 && lastIdx > firstNl) {
                t = t.substring(firstNl + 1, lastIdx).trim();
            }
        }
        return t;
    }

    /**
     * 从预算估算 JSON 中提取 totalCost 数值。
     * M8-3：委托 {@link BudgetJsonParser}（JsonUtils readTree + 异常兜底，取代字符串手工解析）。
     */
    static double extractTotalCost(String budgetJson) {
        return BudgetJsonParser.extractTotalCost(budgetJson);
    }

    /**
     * 从偏好 JSON 中提取 budget 数值（用户预算上限）。
     * M8-3：委托 {@link BudgetJsonParser}。
     */
    static double parseBudgetFromPreference(String preference) {
        return BudgetJsonParser.parseBudget(preference);
    }

    static String formatMoney(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format("%.2f", v);
    }

    /** M14-1b：预算校验 WARNING 合并进 budgetEstimate.notes（非法 JSON 原样保留）。 */
    static Object appendBudgetWarnings(
            String budgetJson, List<ItineraryConflictValidator.Violation> warnings) {
        Object value = toJsonValue(budgetJson);
        if (!(value instanceof ObjectNode obj) || warnings == null || warnings.isEmpty()) {
            return value;
        }
        StringBuilder sb = new StringBuilder("【行程校验提示】");
        for (int i = 0; i < warnings.size(); i++) {
            if (i > 0) {
                sb.append("；");
            }
            sb.append(warnings.get(i).message());
        }
        String oldNotes = obj.path("notes").asText("");
        obj.put("notes", oldNotes.isBlank() ? sb.toString() : oldNotes + "。" + sb);
        return obj;
    }
}
