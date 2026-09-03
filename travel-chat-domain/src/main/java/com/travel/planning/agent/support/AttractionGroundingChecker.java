package com.travel.planning.agent.support;

import com.travel.common.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * M8-2：生成端确定性引用校验（Grounding Check）。
 *
 * <p>延续 M7-8「确定性兜底优先」哲学：查询理解输入端已有 validateLlmType 锚定，
 * 本类把同一思路扩展到<b>生成输出端</b>——校验子 Agent 输出的景点名是否落在
 * 检索候选集中，未命中即可能来自训练数据编造，写入 trace 观测（默认不阻断输出，
 * 避免「故宫 vs 故宫博物院」类名称模糊误伤可用性）。</p>
 *
 * <p>匹配策略：名称归一化（去常见后缀）+ 双向 contains，两者任一命中即算有据。</p>
 */
@Slf4j
@Component
public class AttractionGroundingChecker {

    /** 归一化时移除的常见后缀（剩余长度 &lt; 2 时不移除，避免“故宫”被误删） */
    private static final List<String> SUFFIXES = List.of(
            "博物院", "博物馆", "风景区", "景区", "公园", "寺", "塔");

    /** M8-7：格式化文本行（编号/圆点列表）提取 */
    private static final Pattern LINE_ITEM = Pattern.compile(
            "^\\s*(?:\\d+[.、)]|[-*•])\\s*(.*)$");

    /** 行首 timeSlot 前缀（"09:00-12:00 故宫" → "故宫"） */
    private static final Pattern TIME_PREFIX = Pattern.compile(
            "^\\s*\\d{1,2}:\\d{2}(?:\\s*[-—~至到]\\s*\\d{1,2}:\\d{2})?\\s*");

    /** 非景点行（说明/备注/交通/预算明细等）直接跳过 */
    private static final List<String> SKIP_PREFIX = List.of(
            "备注", "说明", "推荐理由", "交通", "住宿建议", "住宿", "门票",
            "餐饮", "其他", "总费用", "人均");

    /**
     * 校验子 Agent 输出 JSON 中引用的景点名是否落在候选集中。
     *
     * @param candidateNames 候选集景点名（enrich 后 title 集合；空集=无候选，跳过校验）
     * @param outputJson     子 Agent 输出的 attractions JSON 数组文本（或含该数组的整段回答）
     * @return 校验报告；候选集为空或输出不可解析时按“未校验”返回（total=0）
     */
    public GroundingReport check(Set<String> candidateNames, String outputJson) {
        if (candidateNames == null || candidateNames.isEmpty() || outputJson == null
                || outputJson.isBlank()) {
            return new GroundingReport(List.of(), 0, 0, false);
        }
        List<String> names = extractNames(outputJson);
        return buildReport(candidateNames, names);
    }

    /**
     * M8-7：对<b>任意输出文本</b>做引用校验——兼容三种形态：
     * ① JSON 数组（原 check）；② 嵌套 JSON（routePlan {@code days[].attractions[].name}）；
     * ③ 格式化文本（SupervisorResponseFormatter 渲染的编号/圆点列表）。
     *
     * <p>真实链路实证：聊天回答被渲染成「1. 宽窄巷子（文化·…）」、行程 routePlan 是
     * 嵌套 days 结构，原 check 只能解析 JSON 数组元素 name，两处挂载点均静默跳过
     * （groundingRate 从未落库）。本方法统一收口提取后校验。</p>
     */
    public GroundingReport checkText(Set<String> candidateNames, String outputText) {
        if (candidateNames == null || candidateNames.isEmpty()
                || outputText == null || outputText.isBlank()) {
            return new GroundingReport(List.of(), 0, 0, false);
        }
        return buildReport(candidateNames, extractAttractionNames(outputText));
    }

    private static GroundingReport buildReport(Set<String> candidateNames, List<String> names) {
        if (names.isEmpty()) {
            return new GroundingReport(List.of(), 0, 0, false);
        }
        List<String> unmatched = new ArrayList<>();
        int matched = 0;
        for (String name : names) {
            if (matchedAny(candidateNames, name)) {
                matched++;
            } else {
                unmatched.add(name);
            }
        }
        return new GroundingReport(unmatched, names.size(), matched, true);
    }

    /**
     * M8-6：保留性校验（观测）——原行程景点集 vs 新输出景点集，静默丢失率写 trace。
     *
     * @param previousNames 原行程景点名集合
     * @param newNames      新输出景点名集合
     */
    public RetentionReport checkRetention(Set<String> previousNames, Set<String> newNames) {
        if (previousNames == null || previousNames.isEmpty()) {
            return new RetentionReport(List.of(), 0, 0);
        }
        if (newNames == null || newNames.isEmpty()) {
            return new RetentionReport(List.copyOf(previousNames), previousNames.size(), 0);
        }
        List<String> lost = previousNames.stream()
                .filter(p -> newNames.stream().noneMatch(n -> matches(p, n)))
                .toList();
        return new RetentionReport(lost, previousNames.size(), previousNames.size() - lost.size());
    }

    /** 从文本中提取 JSON 数组并解析每个元素的 name 字段（失败返回空列表） */
    private static List<String> extractNames(String text) {
        try {
            String json = extractJsonArray(text);
            if (json == null) {
                return List.of();
            }
            List<?> list = JsonUtils.fromJson(json, List.class);
            if (list == null) {
                return List.of();
            }
            List<String> names = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.get("name") != null) {
                    names.add(String.valueOf(m.get("name")).trim());
                }
            }
            return names.stream().filter(n -> !n.isEmpty()).toList();
        } catch (Exception e) {
            log.debug("[Grounding] 输出 JSON 解析失败，跳过校验: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * M8-6：从任意 JSON 文本（对象/数组/散文混排）中递归提取景点名（name 字段）。
     * 用于会话知识 itinerary_day 切片 → 原行程景点集（保留性校验）。
     */
    public static List<String> extractAttractionNames(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        // ① 纯 JSON（对象/数组/散文混排中的 JSON 数组）：递归提取 name
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    JsonUtils.getMapper().readTree(text);
            if (root == null) {
                return List.of();
            }
            collectNames(root, names);
            if (!names.isEmpty()) {
                return List.copyOf(names);
            }
        } catch (Exception e) {
            // 非纯 JSON：继续走数组/文本行提取
        }
        // ② 散文混排中的 JSON 数组
        String json = extractJsonArray(text);
        if (json != null && !json.equals(text)) {
            try {
                List<?> list = JsonUtils.fromJson(json, List.class);
                if (list != null) {
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> m && m.get("name") != null) {
                            names.add(String.valueOf(m.get("name")).trim());
                        }
                    }
                }
                if (!names.isEmpty()) {
                    return List.copyOf(names);
                }
            } catch (Exception ex) {
                // 忽略，继续文本行提取
            }
        }
        // ③ 格式化文本行（编号/圆点列表）
        names.addAll(extractNamesFromFormattedLines(text));
        return List.copyOf(names);
    }

    /**
     * M8-7：从格式化文本中按行提取景点名。
     * 支持 "1. 故宫博物院（文化·…）" / "- 09:00-12:00 天坛公园（自然）"；
     * 备注/说明/交通等行跳过。
     */
    static List<String> extractNamesFromFormattedLines(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Matcher m = LINE_ITEM.matcher(trimmed);
            if (!m.matches()) {
                continue;
            }
            String item = m.group(1).trim();
            if (item.isEmpty()) {
                continue;
            }
            String lower = item;
            if (SKIP_PREFIX.stream().anyMatch(lower::startsWith)) {
                continue;
            }
            item = TIME_PREFIX.matcher(item).replaceFirst("").trim();
            int paren = item.indexOf("（");
            if (paren > 0) {
                item = item.substring(0, paren).trim();
            }
            if (!item.isEmpty()) {
                names.add(item);
            }
        }
        return List.copyOf(names);
    }

    private static void collectNames(com.fasterxml.jackson.databind.JsonNode node,
                                     Set<String> names) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            var n = node.get("name");
            if (n != null && n.isTextual() && !n.asText().isBlank()) {
                names.add(n.asText().trim());
            }
            node.fields().forEachRemaining(e -> collectNames(e.getValue(), names));
        } else if (node.isArray()) {
            node.forEach(n -> collectNames(n, names));
        }
    }

    /** 提取文本中的 JSON 数组（首个 '[' 到最后一个 ']'；容忍代码围栏/散文前缀） */
    public static String extractJsonArray(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    private static boolean matchedAny(Set<String> candidates, String outputName) {
        return candidates.stream().anyMatch(c -> matches(c, outputName));
    }

    /** 双向 contains 或归一化后相等，任一命中即算匹配 */
    public static boolean matches(String candidate, String outputName) {
        if (candidate == null || outputName == null) {
            return false;
        }
        String c = candidate.trim();
        String o = outputName.trim();
        if (c.isEmpty() || o.isEmpty()) {
            return false;
        }
        if (c.contains(o) || o.contains(c)) {
            return true;
        }
        return normalize(c).equals(normalize(o));
    }

    /** 去常见后缀（剩余长度 ≥2）；"故宫博物院" → "故宫"、"天坛公园" → "天坛" */
    static String normalize(String name) {
        String n = name == null ? "" : name.trim();
        for (String suffix : SUFFIXES) {
            if (n.length() - suffix.length() >= 2 && n.endsWith(suffix)) {
                n = n.substring(0, n.length() - suffix.length()).trim();
            }
        }
        return n;
    }

    /**
     * 引用校验报告。
     *
     * @param checked false=未校验（候选为空/输出不可解析），rate 无意义
     */
    public record GroundingReport(List<String> unmatchedNames, int total, int matched,
                                  boolean checked) {
        public double rate() {
            return total == 0 ? 1.0 : (double) matched / total;
        }
    }

    /** M8-6：保留性观测报告（lost=原行程中有、新输出中无的景点名） */
    public record RetentionReport(List<String> lostNames, int total, int kept) {
        public double rate() {
            return total == 0 ? 1.0 : (double) kept / total;
        }
    }
}
