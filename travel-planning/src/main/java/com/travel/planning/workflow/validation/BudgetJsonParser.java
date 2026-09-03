package com.travel.planning.workflow.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.common.util.AgentOutputUtils;
import com.travel.common.util.JsonUtils;

/**
 * M8-3：预算 JSON 结构化解析（搭车治理 §2.3）。
 *
 * <p>取代 TravelWorkflowBuilder 旧实现「indexOf + 正则手工抽取」——嵌套 JSON、
 * 数值带单位/千分位、字段缺失等场景下旧实现会静默失准（预算判定失准直接导致
 * budget_retry 误判）。统一 {@link JsonUtils} readTree + 路径取值 + 异常兜底。</p>
 */
public final class BudgetJsonParser {

    private BudgetJsonParser() {
    }

    /**
     * 从预算估算 JSON 提取 totalCost。
     *
     * @return 数值；缺失/不可解析返回 0（与原字符串解析语义一致）
     */
    public static double extractTotalCost(String budgetJson) {
        JsonNode node = parse(strip(budgetJson));
        if (node == null) {
            return 0;
        }
        return toDouble(node.get("totalCost"), 0);
    }

    /**
     * 从预算估算 JSON 提取 ticketCost（M8-3 费用一致性校验用）。
     *
     * @return 数值；缺失/不可解析返回 0
     */
    public static double extractTicketCost(String budgetJson) {
        JsonNode node = parse(strip(budgetJson));
        if (node == null) {
            return 0;
        }
        return toDouble(node.get("ticketCost"), 0);
    }

    /**
     * 从偏好 JSON 提取预算上限。
     *
     * @return 数值；缺失/null/不可解析返回 {@link Double#MAX_VALUE}（保持原语义）
     */
    public static double parseBudget(String preferenceJson) {
        JsonNode node = parse(strip(preferenceJson));
        if (node == null) {
            return Double.MAX_VALUE;
        }
        JsonNode budget = node.get("budget");
        if (budget == null || budget.isNull() || budget.isMissingNode()) {
            return Double.MAX_VALUE;
        }
        return toDouble(budget, Double.MAX_VALUE);
    }

    private static JsonNode parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return JsonUtils.getMapper().readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    /** 剥离 Markdown 代码围栏（复用 common 单源工具） */
    private static String strip(String text) {
        return text == null ? null : AgentOutputUtils.stripCodeFence(text);
    }

    /** 数值节点直接取值；文本节点提取首个十进制数；其余返回默认值 */
    private static double toDouble(JsonNode n, double fallback) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return fallback;
        }
        if (n.isNumber()) {
            return n.asDouble(fallback);
        }
        if (n.isTextual()) {
            String t = n.asText().trim().replace(",", "");
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("\\d+(?:\\.\\d+)?").matcher(t);
            if (m.find()) {
                try {
                    return Double.parseDouble(m.group());
                } catch (NumberFormatException e) {
                    return fallback;
                }
            }
        }
        return fallback;
    }
}
