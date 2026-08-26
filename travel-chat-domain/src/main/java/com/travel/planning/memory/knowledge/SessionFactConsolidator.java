package com.travel.planning.memory.knowledge;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 会话事实共识（F85）：把同主题 constraint/feedback 合并为"最新确认口径"，
 * 在注入前消解冲突（如 feedback"改成3000"覆盖旧 constraint"预算5000"）。
 *
 * <p>只读视图：不写库、不改画像；无命中或解析失败时不输出，不影响其它注入。</p>
 */
@Component
public class SessionFactConsolidator {

    /** 共识主题 */
    public enum Topic {
        BUDGET("预算"), DESTINATION("目的地"), DAYS("天数"),
        PARTY("人数"), STYLE("风格"), INTEREST("兴趣");

        final String label;

        Topic(String label) {
            this.label = label;
        }
    }

    /** 共识条目：同主题内 createdAt 晚者胜；feedback 覆盖旧 constraint */
    public record ConsensusEntry(Topic topic, String value, String type, String createdAt) {
    }

    // 兼容："预算3000元" / "预算3000" / "改成3000" / "预算是3000" / "3000元"
    // 数字部分兼容千分位（3,000）：[0-9]+(?:,[0-9]{3})*(?:\.[0-9]+)?
    private static final String NUM = "[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?";
    private static final Pattern BUDGET_PATTERN =
            Pattern.compile("预算\\s*(" + NUM + ")\\s*元?"
                    + "|(?:改成|是|为|上限|控制在)\\s*(" + NUM + ")\\s*元?"
                    + "|(" + NUM + ")\\s*元");
    // 中文数字（三千/五千/一万/两万等），F85 U4：与数字写法统一归一化
    private static final Pattern CHINESE_NUMBER_PATTERN =
            Pattern.compile("([零一二两三四五六七八九十百千万]+)\\s*元?");
    private static final Map<Character, Integer> CN_DIGIT = new HashMap<>();
    static {
        CN_DIGIT.put('零', 0);
        CN_DIGIT.put('一', 1);
        CN_DIGIT.put('二', 2);
        CN_DIGIT.put('两', 2);
        CN_DIGIT.put('三', 3);
        CN_DIGIT.put('四', 4);
        CN_DIGIT.put('五', 5);
        CN_DIGIT.put('六', 6);
        CN_DIGIT.put('七', 7);
        CN_DIGIT.put('八', 8);
        CN_DIGIT.put('九', 9);
    }
    // F81 教训：不用"日"——"3日游"会被误判为天数；只认"天"
    private static final Pattern DAYS_PATTERN =
            Pattern.compile("([0-9]+)\\s*天");

    /**
     * 合并检索结果中的 constraint/feedback 切片。
     *
     * @param hits {@link SessionKnowledgeWriter#searchStructured} 的返回值
     * @return 按主题聚合后的最新确认条目（无命中返回空列表）
     */
    public List<ConsensusEntry> consolidate(List<Map<String, Object>> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        Map<Topic, List<Map<String, Object>>> byTopic = new LinkedHashMap<>();
        for (Map<String, Object> hit : hits) {
            String type = String.valueOf(hit.getOrDefault("type", ""));
            if (!"constraint".equals(type) && !"feedback".equals(type)) {
                continue;
            }
            Topic topic = detectTopic(String.valueOf(hit.getOrDefault("content", "")));
            if (topic == null) {
                continue;
            }
            byTopic.computeIfAbsent(topic, k -> new ArrayList<>()).add(hit);
        }
        List<ConsensusEntry> result = new ArrayList<>();
        for (Map.Entry<Topic, List<Map<String, Object>>> e : byTopic.entrySet()) {
            List<Map<String, Object>> list = e.getValue();
            // createdAt 晚者胜；同 createdAt 时 feedback（权重1）优先于 constraint（权重0）
            list.sort((a, b) -> {
                int c = String.valueOf(a.getOrDefault("createdAt", ""))
                        .compareTo(String.valueOf(b.getOrDefault("createdAt", "")));
                if (c != 0) {
                    return c;
                }
                return Integer.compare(typeRank(a), typeRank(b));
            });
            Map<String, Object> latest = list.get(list.size() - 1);
            String value = normalize(e.getKey(), String.valueOf(latest.getOrDefault("content", "")));
            boolean overridden = list.size() > 1 && "feedback".equals(typeOf(latest));
            result.add(new ConsensusEntry(e.getKey(),
                    overridden ? value + "（已修正）" : value,
                    typeOf(latest), String.valueOf(latest.getOrDefault("createdAt", ""))));
        }
        return result;
    }

    /** 渲染注入段文本；空列表返回空串 */
    public String render(List<ConsensusEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【会话最新确认】\n");
        for (ConsensusEntry e : entries) {
            sb.append("- ").append(e.topic().label).append("：").append(e.value()).append("\n");
        }
        return sb.toString().trim();
    }

    private static Topic detectTopic(String content) {
        // 预算优先，但含"天/日"的天数类表达（如"天数改成4天"）不被预算正则误判
        boolean hasDayMarker = content.contains("天") || content.contains("日");
        if (content.contains("预算") || content.contains("元")
                || (BUDGET_PATTERN.matcher(content).find() && !hasDayMarker)) {
            return Topic.BUDGET;
        }
        if (DAYS_PATTERN.matcher(content).find()) {
            return Topic.DAYS;
        }
        if (containsAny(content, "想去", "去北京", "去上海", "去广州", "去深圳", "去杭州",
                "去成都", "去西安", "去厦门", "去南京", "去重庆", "去武汉", "去长沙")) {
            return Topic.DESTINATION;
        }
        if (containsAny(content, "带小孩", "带娃", "亲子", "家庭", "情侣", "独行", "朋友", "2人", "3人")) {
            return Topic.PARTY;
        }
        if (containsAny(content, "舒适", "经济", "豪华", "穷游", "高性价比")) {
            return Topic.STYLE;
        }
        if (content.contains("喜欢") || content.contains("爱好")) {
            return Topic.INTEREST;
        }
        return null;
    }

    /** 数字归一化（预算/天数），失败保留原文 */
    private static String normalize(Topic topic, String content) {
        if (topic == Topic.BUDGET) {
            Matcher m = BUDGET_PATTERN.matcher(content);
            if (m.find()) {
                String num = m.group(1) != null ? m.group(1)
                        : (m.group(2) != null ? m.group(2) : m.group(3));
                return num.replace(",", "") + "元";
            }
            Matcher cn = CHINESE_NUMBER_PATTERN.matcher(content);
            if (cn.find()) {
                Integer val = parseChineseNumber(cn.group(1));
                if (val != null) {
                    return val + "元";
                }
            }
        }
        if (topic == Topic.DAYS) {
            Matcher m = DAYS_PATTERN.matcher(content);
            if (m.find()) {
                return m.group(1) + "天";
            }
        }
        return content;
    }

    /** 解析"三千/五千/一万/两万/三千五"等常见中文数字；不支持返回 null */
    private static Integer parseChineseNumber(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        int total = 0;
        int section = 0;
        int num = 0;
        for (char c : s.toCharArray()) {
            Integer d = CN_DIGIT.get(c);
            if (d != null) {
                num = d;
            } else if (c == '十') {
                section += (num == 0 ? 1 : num) * 10;
                num = 0;
            } else if (c == '百') {
                section += (num == 0 ? 1 : num) * 100;
                num = 0;
            } else if (c == '千') {
                section += (num == 0 ? 1 : num) * 1000;
                num = 0;
            } else if (c == '万') {
                total = (total + section + num) * 10000;
                section = 0;
                num = 0;
            } else {
                return null;
            }
        }
        return total + section + num;
    }

    private static boolean containsAny(String text, String... tokens) {
        return com.travel.common.util.AgentOutputUtils.containsAny(text, tokens);
    }

    private static String typeOf(Map<String, Object> hit) {
        return String.valueOf(hit.getOrDefault("type", ""));
    }

    /** feedback=1 / constraint=0，用于 createdAt 相同时的稳定排序 */
    private static int typeRank(Map<String, Object> hit) {
        return "feedback".equals(typeOf(hit)) ? 1 : 0;
    }
}
