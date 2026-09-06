package com.travel.planning.memory.longterm.behavior;

import com.travel.common.entity.UserBehaviorProfile;
import com.travel.common.util.JsonUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * M17-3：【行为特征】注入段渲染（纯函数，零存储依赖）。
 *
 * <p>把行为画像聚合行渲染为<strong>单行</strong>注入段（与画像既有段落同粒度，
 * 便于纳入统一的 token 截断体系——作为最后一个 part 追加，预算超限时天然最先被截）。
 * 子项按重要性排列，超出注入预算时自尾向前丢弃；仅剩前缀时返回 null（不注入）。</p>
 */
public final class BehaviorSections {

    private BehaviorSections() {
    }

    /**
     * 渲染【行为特征】段。
     *
     * @param row       行为画像（null 或无可渲染子项 → null）
     * @param maxTokens 注入段 token 预算（中文≈1 token/字，其他≈0.25/字）
     */
    public static String render(UserBehaviorProfile row, int maxTokens) {
        if (row == null || maxTokens <= 0) {
            return null;
        }
        List<String> items = new ArrayList<>();
        // 1) 活跃时段（峰值带）
        int[] hours = parseHours(row.getActiveHours());
        String peak = BehaviorProfileService.activeHourPeakText(hours);
        if (peak != null) {
            items.add("活跃时段：" + peak);
        }
        // 2) 预算档位（P25~P75；门禁2评审修复4：P75 判空，避免渲染 "null" 文本）
        if (row.getBudgetP25() != null && row.getBudgetP75() != null) {
            items.add("预算档位：" + strip(row.getBudgetP25()) + "~" + strip(row.getBudgetP75()) + "元为主");
        } else if (row.getBudgetP50() != null) {
            items.add("预算档位：" + strip(row.getBudgetP50()) + "元左右");
        }
        // 3) 高频目的地（top3，cnt≥2 才算高频）
        List<String> tops = topDestinations(row.getDestFreq(), 3);
        if (!tops.isEmpty()) {
            items.add("高频目的地：" + String.join("、", tops));
        }
        // 4) 常见同行（置信度 ≥60%）
        if (row.getPartyMode() != null && row.getPartyConfidence() != null
                && row.getPartyConfidence() >= 60) {
            items.add("常见同行：" + row.getPartyMode());
        }
        // 5) 规划习惯（平均微调次数）
        if (row.getRefineAvg() != null && row.getRefineAvg().compareTo(BigDecimal.valueOf(0.5)) >= 0) {
            items.add("规划习惯：生成后平均微调 " + strip(row.getRefineAvg()) + " 次");
        }
        if (items.isEmpty()) {
            return null;
        }
        // 预算裁剪：自尾向前丢弃低重要性子项
        String prefix = "【行为特征】";
        while (!items.isEmpty()) {
            String candidate = prefix + String.join("；", items);
            if (estimateTokens(candidate) <= maxTokens) {
                return candidate;
            }
            items.remove(items.size() - 1);
        }
        return null;
    }

    private static List<String> topDestinations(String destFreqJson, int limit) {
        if (destFreqJson == null || destFreqJson.isBlank()) {
            return List.of();
        }
        try {
            List<Map> rows = JsonUtils.parseList(destFreqJson, Map.class);
            List<String> tops = new ArrayList<>();
            if (rows != null) {
                for (Map<?, ?> r : rows) {
                    Object cnt = r.get("cnt");
                    Object city = r.get("city");
                    if (city != null && cnt instanceof Number n && n.intValue() >= 2) {
                        tops.add(String.valueOf(city));
                        if (tops.size() >= limit) {
                            break;
                        }
                    }
                }
            }
            return tops;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static int[] parseHours(String json) {
        if (json == null || json.isBlank()) {
            return new int[24];
        }
        try {
            List<Integer> list = JsonUtils.parseList(json, Integer.class);
            int[] hours = new int[24];
            if (list != null) {
                for (int i = 0; i < Math.min(list.size(), 24); i++) {
                    hours[i] = list.get(i) == null ? 0 : list.get(i);
                }
            }
            return hours;
        } catch (Exception ignored) {
            return new int[24];
        }
    }

    private static String strip(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static int estimateTokens(String text) {
        double cost = 0;
        for (char c : text.toCharArray()) {
            cost += Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN ? 1.0 : 0.25;
        }
        return (int) Math.ceil(cost);
    }
}
