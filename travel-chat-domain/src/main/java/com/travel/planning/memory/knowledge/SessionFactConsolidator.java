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

    // M18-1：主题词表单源 travel.chat.word-lists.fact（cities 与 knowledge 18 城对齐）
    private final com.travel.planning.config.ChatWordLists wordLists;

    public SessionFactConsolidator(com.travel.planning.config.ChatWordLists wordLists) {
        this.wordLists = wordLists;
    }


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

    /**
     * M28-10：同行人规范值映射（与 travel-planning ItineraryVersionPortImpl#parseParty 同口径）。
     * 词表未覆盖的写法（如"2人/3人"）返回命中词本身，保守不臆造。
     */
    private static final Map<String, String> PARTY_CANONICAL = Map.ofEntries(
            Map.entry("带小孩", "家庭"), Map.entry("带娃", "家庭"), Map.entry("亲子", "家庭"),
            Map.entry("家庭", "家庭"), Map.entry("家人", "家庭"),
            Map.entry("情侣", "情侣"), Map.entry("夫妻", "情侣"), Map.entry("两个人", "情侣"),
            Map.entry("朋友", "朋友"), Map.entry("闺蜜", "朋友"), Map.entry("同事", "朋友"),
            Map.entry("独行", "独行"), Map.entry("一个人", "独行"));

    // 兼容："预算3000元" / "预算3000" / "改成3000" / "预算是3000" / "3000元"
    // 数字部分兼容千分位（3,000）：[0-9]+(?:,[0-9]{3})*(?:\.[0-9]+)?
    private static final String NUM = "[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?";
    // M28-1（热修）：预算数字按"显式词 → 动词 → 裸元"优先级依次匹配，
    // 不再单正则交替（交替正则按最左命中——"改成5日游，预算8000"会被
    // 动词分支的"改成5"抢先，返回 5 元的错误口径并注入提示词）
    private static final Pattern BUDGET_EXPLICIT =
            Pattern.compile("预算\\s*(" + NUM + ")\\s*元?");
    private static final Pattern BUDGET_VERB =
            Pattern.compile("(?:改成|是|为|上限|控制在)\\s*(" + NUM + ")\\s*元?");
    private static final Pattern BUDGET_BARE =
            Pattern.compile("(" + NUM + ")\\s*元");
    /** 兼容 detectTopic 的"任意预算表达存在"判定（口径选择交给 budgetNumberOf） */
    private static final Pattern BUDGET_ANY =
            Pattern.compile("预算|" + BUDGET_VERB + "|" + BUDGET_BARE);
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
            String content = String.valueOf(hit.getOrDefault("content", ""));
            // M28-10：多主题归属——一条陈述可同时携带多个主题（"同行人改成情侣，
            // 天数改成5天"=PARTY+DAYS）。旧单主题 if-else 把它只归 DAYS，PARTY 信息
            // 整条丢失，组内只剩最旧 constraint"独行"，连续多轮注入错误口径，
            // 诱导图流 LLM 把同行人私改回独行（2026-09-07 21:12/21:14 实测日志实证）
            for (Topic topic : detectTopics(content)) {
                byTopic.computeIfAbsent(topic, k -> new ArrayList<>()).add(hit);
            }
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

    /**
     * M28-10：多主题归属——一条陈述可同时命中多个主题，各自独立成组参与
     * "最新者胜"合并；无命中返回空列表（不注入，不影响其它主题）。
     */
    private List<Topic> detectTopics(String content) {
        List<Topic> topics = new ArrayList<>();
        // 预算优先，但含"天/日"的天数类表达（如"天数改成4天"）不被预算正则误判
        boolean hasDayMarker = content.contains("天") || content.contains("日");
        if (content.contains("预算") || content.contains("元")
                || (BUDGET_ANY.matcher(content).find() && !hasDayMarker)) {
            topics.add(Topic.BUDGET);
        }
        if (DAYS_PATTERN.matcher(content).find()) {
            topics.add(Topic.DAYS);
        }
        if (content.contains("想去")
                || containsAny(content, wordLists.getFact().getCities().stream()
                        .map(c -> "去" + c).toArray(String[]::new))) {
            topics.add(Topic.DESTINATION);
        }
        if (containsAny(content, wordLists.getFact().getPartyPatterns().toArray(new String[0]))) {
            topics.add(Topic.PARTY);
        }
        if (containsAny(content, wordLists.getFact().getStylePatterns().toArray(new String[0]))) {
            topics.add(Topic.STYLE);
        }
        if (content.contains("喜欢") || content.contains("爱好")) {
            topics.add(Topic.INTEREST);
        }
        return topics;
    }

    /**
     * 按优先级提取预算数字（M28-1 热修）：
     * ① 断言类（显式"预算N"与动词"改成/是/为/上限/控制在 N"）同场竞争，取<b>位置最靠后</b>
     *    的命中=用户最新口径（"预算8000改成7000"→7000；动词跳过紧邻"天/日"的天数表达，
     *    如"改成5日游，预算8000"→8000 而非 5）；
     * ② 裸"N元"仅在无任何断言命中时兜底（最低优先级——"预算5000，车票300元"不得被 300 覆盖）。
     * 均未命中返回 null。
     */
    private static String budgetNumberOf(String content) {
        String best = null;
        int bestStart = -1;
        Matcher m = BUDGET_EXPLICIT.matcher(content);
        while (m.find()) {
            if (m.start() >= bestStart) {
                bestStart = m.start();
                best = m.group(1);
            }
        }
        m = BUDGET_VERB.matcher(content);
        while (m.find()) {
            int end = m.end(1);
            if (end < content.length()) {
                char next = content.charAt(end);
                if (next == '天' || next == '日') {
                    continue; // "改成5日游"的 5 是天数不是预算
                }
            }
            if (m.start() >= bestStart) {
                bestStart = m.start();
                best = m.group(1);
            }
        }
        if (best != null) {
            return best;
        }
        m = BUDGET_BARE.matcher(content);
        while (m.find()) {
            best = m.group(1);
        }
        return best;
    }

    /** 值归一化（预算/天数/目的地/同行人），失败保留原文 */
    private String normalize(Topic topic, String content) {
        if (topic == Topic.BUDGET) {
            String num = budgetNumberOf(content);
            if (num != null) {
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
        // M28-10：目的地/同行人输出规范值——此前 PARTY 注入的是用户原句
        // （"人数：同行人帮我设置为独行"），LLM 需自行解读，易被放大为错误操作
        if (topic == Topic.DESTINATION) {
            for (String city : wordLists.getFact().getCities()) {
                if (content.contains(city)) {
                    return city;
                }
            }
        }
        if (topic == Topic.PARTY) {
            for (String p : wordLists.getFact().getPartyPatterns()) {
                if (content.contains(p)) {
                    return PARTY_CANONICAL.getOrDefault(p, p);
                }
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
