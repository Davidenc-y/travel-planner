package com.travel.planning.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * M18-2：聊天建行程/REFINE 回写的确定性解析词表（travel.itinerary-writeback.parse.*）。
 *
 * <p>此前 6 个正则与城市脏词黑名单（补丁式增长词表）硬编码于
 * {@code ItineraryVersionPortImpl}；本类 yml 单源（planning application.yml），
 * 值与原代码字面量逐字一致（行为等价红线）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.itinerary-writeback.parse")
public class ItineraryWritebackProperties {

    /** 天数（数字）：(\d{1,2})\s*(?:日游|天) */
    private String daysDigit = "(\\d{1,2})\\s*(?:日游|天)";

    /** 天数（中文数字） */
    private String daysCn = "(?<![第])([一二两三四五六七八九十]+)\\s*(?:日游|天)";

    /** 目的地：规划式（规划/安排/推荐/设计…N日游） */
    private String destPlan = "(?:规划|安排|推荐|设计)(?:一下|一个|一次)?\\s*([\\u4e00-\\u9fa5]{2,10}?)"
            + "\\s*(?:的)?(?:\\d{1,2}|[一二两三四五六七八九十]+)\\s*(?:日游|天)";

    /** 目的地：去向式（去/到…玩/旅游…） */
    private String destGo = "(?:去|到)([\\u4e00-\\u9fa5]{2,10}?)"
            + "(?:玩|旅游|旅行|游玩|度假|，|,|\\s|\\d)";

    /** 目的地：句首式（成都3日游） */
    private String destLeading = "^([\\u4e00-\\u9fa5]{2,10}?)\\s*(?:的)?"
            + "(?:\\d{1,2}|[一二两三四五六七八九十]+)\\s*(?:日游|天)";

    /** 预算 */
    private String budget = "预算\\s*(?:约)?\\s*(\\d+(?:\\.\\d+)?)";

    /** 城市名脏词黑名单（normalizeCity 过滤补丁词表） */
    private List<String> cityBlocklist = List.of(
            "避开人流", "宽窄巷子", "第一天", "第二天", "改到", "游玩",
            "把", "第", "到", "改", "的");

    /** 城市名长度区间（含） */
    private int cityNameMinLen = 2;
    private int cityNameMaxLen = 4;

    // ---------------- 解析器（M18-2：词表与算法同处，供回写/天气注入共用） ----------------

    private transient java.util.regex.Pattern pDaysDigit;
    private transient java.util.regex.Pattern pDaysCn;
    private transient java.util.regex.Pattern pDestPlan;
    private transient java.util.regex.Pattern pDestGo;
    private transient java.util.regex.Pattern pDestLeading;
    private transient java.util.regex.Pattern pBudget;
    private transient boolean compiled;

    /** 绑定完成后编译正则（非法配置快速失败；直构场景懒编译兜底）。 */
    @jakarta.annotation.PostConstruct
    public void init() {
        ensureCompiled();
    }

    private synchronized void ensureCompiled() {
        if (compiled) {
            return;
        }
        pDaysDigit = java.util.regex.Pattern.compile(daysDigit);
        pDaysCn = java.util.regex.Pattern.compile(daysCn);
        pDestPlan = java.util.regex.Pattern.compile(destPlan);
        pDestGo = java.util.regex.Pattern.compile(destGo);
        pDestLeading = java.util.regex.Pattern.compile(destLeading);
        pBudget = java.util.regex.Pattern.compile(budget);
        compiled = true;
    }

    /** 天数解析：数字优先，中文数字兜底。 */
    public Integer parseDays(String input) {
        ensureCompiled();
        String question = currentQuestion(input);
        if (question == null) {
            return null;
        }
        java.util.regex.Matcher m = pDaysDigit.matcher(question);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        java.util.regex.Matcher cn = pDaysCn.matcher(question);
        if (cn.find()) {
            return chineseNumber(cn.group(1));
        }
        return null;
    }

    /** 目的地解析：规划式 → 去向式 → 句首式；均过城市归一。 */
    public String parseDestination(String input) {
        ensureCompiled();
        String question = currentQuestion(input);
        if (question == null || question.isBlank()) {
            return null;
        }
        java.util.regex.Matcher plan = pDestPlan.matcher(question);
        if (plan.find()) {
            return normalizeCity(plan.group(1));
        }
        java.util.regex.Matcher go = pDestGo.matcher(question);
        if (go.find()) {
            return normalizeCity(go.group(1));
        }
        java.util.regex.Matcher leading = pDestLeading.matcher(question.trim());
        if (leading.find()) {
            return normalizeCity(leading.group(1));
        }
        return null;
    }

    /** 预算解析。 */
    public java.math.BigDecimal parseBudget(String input) {
        ensureCompiled();
        String question = currentQuestion(input);
        if (question == null) {
            return null;
        }
        java.util.regex.Matcher m = pBudget.matcher(question);
        if (m.find()) {
            try {
                return new java.math.BigDecimal(m.group(1));
            } catch (NumberFormatException ignored) {
                // 非法数字忽略
            }
        }
        return null;
    }

    /** 从完整 composed 上下文中截取"【当前问题】"之后的用户原始问题。 */
    private String currentQuestion(String input) {
        if (input == null) {
            return null;
        }
        String marker = com.travel.planning.prompt.Markers.CURRENT_QUESTION;
        int idx = input.lastIndexOf(marker);
        String question = idx >= 0 ? input.substring(idx + marker.length()) : input;
        int userId = question.lastIndexOf(com.travel.planning.prompt.Markers.LEGACY_USER_ID_SUFFIX);
        if (userId >= 0) {
            question = question.substring(0, userId);
        }
        return question.trim();
    }

    private String normalizeCity(String city) {
        String c = city == null ? "" : city.trim();
        if (c.endsWith("市") && c.length() > 2) {
            c = c.substring(0, c.length() - 1);
        }
        if (c.isEmpty() || c.length() < cityNameMinLen || c.length() > cityNameMaxLen) {
            return null;
        }
        for (String block : cityBlocklist) {
            if (c.contains(block)) {
                return null;
            }
        }
        return c;
    }

    private static Integer chineseNumber(String cn) {
        char[] chars = cn.toCharArray();
        int sum = 0;
        for (char ch : chars) {
            int v = switch (ch) {
                case '一' -> 1;
                case '二', '两' -> 2;
                case '三' -> 3;
                case '四' -> 4;
                case '五' -> 5;
                case '六' -> 6;
                case '七' -> 7;
                case '八' -> 8;
                case '九' -> 9;
                case '十' -> 10;
                default -> -1;
            };
            if (v < 0) {
                return null;
            }
            sum += v;
        }
        return sum;
    }
}
