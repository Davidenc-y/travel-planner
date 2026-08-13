package com.travel.planning.agent.supervisor;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.common.util.JsonUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * F27：把子 Agent 输出的 JSON 排版为面向用户的可读文本。
 *
 * <p>原始 response 是把四个子 Agent 的 JSON 原样拼接（内嵌 JSON 导致大量 {@code \"} 转义、
 * 可读性差）。本排版器解析各段 JSON 后渲染为自然语言文本；解析失败或结构不符时
 * 回退原始文本，保证内容不丢失。</p>
 */
final class SupervisorResponseFormatter {

    private SupervisorResponseFormatter() {
    }

    /**
     * 按段落标题排版。raw 为空返回 null；解析失败回退 cleaned 原文。
     */
    static String format(String title, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.trim();
        return switch (title) {
            case "偏好分析" -> formatPreference(cleaned);
            case "推荐景点" -> formatAttractions(cleaned);
            case "每日行程" -> formatRoutePlan(cleaned);
            case "预算估算" -> formatBudget(cleaned);
            default -> cleaned;
        };
    }

    private static JsonNode parse(String text) {
        try {
            return JsonUtils.getMapper().readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 偏好分析 ====================

    private static String formatPreference(String json) {
        JsonNode node = parse(json);
        if (node == null || !node.isObject()) {
            return json;
        }
        List<String> items = new ArrayList<>();
        addPreference(items, "目的地", node.get("destination"));
        addPreference(items, "天数", node.get("days"));
        addPreference(items, "预算", node.get("budget"));
        addPreference(items, "兴趣", node.get("interests"));
        addPreference(items, "出行人员", node.get("party"));
        addPreference(items, "出行风格", node.get("travelStyle"));
        addPreference(items, "特殊需求", node.get("specialNeeds"));
        return items.isEmpty() ? json : String.join("；", items);
    }

    private static void addPreference(List<String> items, String label, JsonNode value) {
        if (value == null || value.isNull()) {
            return;
        }
        String text = nodeText(value);
        if (text == null || text.isBlank() || "null".equals(text)) {
            return;
        }
        items.add(label + "：" + text);
    }

    /** 数组按顿号连接；空值/空数组补"未指定"。 */
    private static String nodeText(JsonNode value) {
        if (value == null || value.isNull()) {
            return "未指定";
        }
        if (value.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode item : value) {
                if (!item.isNull() && !item.asText().isBlank()) {
                    parts.add(item.asText());
                }
            }
            return parts.isEmpty() ? "未指定" : String.join("、", parts);
        }
        String t = value.asText();
        return t == null || t.isBlank() ? "未指定" : t;
    }

    // ==================== 推荐景点 ====================

    private static String formatAttractions(String json) {
        JsonNode node = parse(json);
        if (node == null || !node.isArray()) {
            return json;
        }
        if (node.isEmpty()) {
            return "暂无推荐景点";
        }
        List<String> lines = new ArrayList<>();
        int idx = 1;
        for (JsonNode item : node) {
            StringBuilder head = new StringBuilder(idx + ". " + safe(item.get("name")));
            List<String> attrs = new ArrayList<>();
            addAttr(attrs, safe(item.get("type")));
            addAttr(attrs, safe(item.get("duration")));
            addAttr(attrs, cost(item.get("cost")));
            addAttr(attrs, rating(item.get("rating")));
            if (!attrs.isEmpty()) {
                head.append("（").append(String.join("·", attrs)).append("）");
            }
            lines.add(head.toString());
            String reason = safe(item.get("reason"));
            if (!reason.isBlank()) {
                lines.add("   推荐理由：" + reason);
            }
            idx++;
        }
        return String.join("\n", lines);
    }

    private static void addAttr(List<String> attrs, String text) {
        if (text != null && !text.isBlank()) {
            attrs.add(text);
        }
    }

    private static String cost(JsonNode value) {
        if (value == null || value.isNull()) {
            return "";
        }
        String t = numberText(value);
        return t.isBlank() ? "" : t + "元";
    }

    private static String rating(JsonNode value) {
        if (value == null || value.isNull()) {
            return "";
        }
        return "评分" + value.asText();
    }

    // ==================== 每日行程 ====================

    private static String formatRoutePlan(String json) {
        JsonNode node = parse(json);
        if (node == null || !node.isObject() || !node.has("days") || !node.get("days").isArray()) {
            return json;
        }
        JsonNode days = node.get("days");
        if (days.isEmpty()) {
            return "暂无具体行程安排";
        }
        List<String> lines = new ArrayList<>();
        for (JsonNode day : days) {
            String dayNo = day.hasNonNull("day") ? day.get("day").asText() : "?";
            String date = day.hasNonNull("date") ? day.get("date").asText() : "";
            String summary = day.hasNonNull("summary") ? day.get("summary").asText() : "";
            StringBuilder head = new StringBuilder("第").append(dayNo).append("天");
            if (!date.isBlank()) {
                head.append("（").append(date).append("）");
            }
            if (!summary.isBlank()) {
                head.append("：").append(summary);
            }
            lines.add(head.toString());

            JsonNode attrs = day.get("attractions");
            if (attrs != null && attrs.isArray()) {
                for (JsonNode attr : attrs) {
                    String name = safe(attr.get("name"));
                    String timeSlot = safe(attr.get("timeSlot"));
                    String c = cost(attr.get("cost"));
                    String notes = safe(attr.get("notes"));
                    StringBuilder line = new StringBuilder("  - ");
                    if (!timeSlot.isBlank()) {
                        line.append(timeSlot).append(" ");
                    }
                    line.append(name);
                    if (!c.isBlank()) {
                        line.append("（").append(c).append("）");
                    }
                    lines.add(line.toString());
                    if (!notes.isBlank()) {
                        lines.add("    备注：" + notes);
                    }
                }
            }

            String transport = safe(day.get("transportMode"));
            if (!transport.isBlank()) {
                lines.add("  交通：" + transport);
            }
            String hotel = safe(day.get("hotelSuggestion"));
            if (!hotel.isBlank()) {
                lines.add("  住宿建议：" + hotel);
            }
        }
        return String.join("\n", lines);
    }

    // ==================== 预算估算 ====================

    private static String formatBudget(String json) {
        JsonNode node = parse(json);
        if (node == null || !node.isObject()) {
            return json;
        }
        List<String> lines = new ArrayList<>();
        String total = node.hasNonNull("totalCost") ? numberText(node.get("totalCost")) + "元" : "";
        String per = node.hasNonNull("perPersonCost") ? numberText(node.get("perPersonCost")) + "元" : "";
        String currency = node.hasNonNull("currency") ? node.get("currency").asText() : "";

        StringBuilder head = new StringBuilder("总费用：").append(total);
        List<String> extra = new ArrayList<>();
        if (!currency.isBlank()) {
            extra.add(currency);
        }
        if (!per.isBlank()) {
            extra.add("人均" + per);
        }
        if (!extra.isEmpty()) {
            head.append("（").append(String.join("，", extra)).append("）");
        }
        lines.add(head.toString());

        addBudgetItem(lines, "门票", node.get("ticketCost"));
        addBudgetItem(lines, "餐饮", node.get("mealCost"));
        addBudgetItem(lines, "交通", node.get("transportCost"));
        addBudgetItem(lines, "住宿", node.get("hotelCost"));
        addBudgetItem(lines, "其他", node.get("otherCost"));

        String notes = safe(node.get("notes"));
        if (!notes.isBlank()) {
            lines.add("说明：" + notes);
        }
        return String.join("\n", lines);
    }

    private static void addBudgetItem(List<String> lines, String label, JsonNode value) {
        if (value == null || value.isNull()) {
            return;
        }
        lines.add("  - " + label + "：" + numberText(value) + "元");
    }

    // ==================== 通用 ====================

    /** 数值文本：整数去掉 .0，浮点保留小数。 */
    private static String numberText(JsonNode value) {
        if (value == null || value.isNull()) {
            return "";
        }
        if (value.isIntegralNumber()) {
            return value.asLong() + "";
        }
        if (value.isFloatingPointNumber()) {
            double d = value.asDouble();
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return (long) d + "";
            }
            return value.asText();
        }
        return value.asText();
    }

    private static String safe(JsonNode value) {
        return value == null || value.isNull() ? "" : value.asText();
    }
}
