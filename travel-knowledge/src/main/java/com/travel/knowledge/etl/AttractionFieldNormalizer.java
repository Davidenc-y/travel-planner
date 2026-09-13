package com.travel.knowledge.etl;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.common.util.JsonUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DG-1a：景点字段规范化器（纯新增，纯函数零 IO）。
 *
 * <p>三方法：{@link #normalizeOpenHours}（营业时间"HH:mm-HH:mm"段/"全天"/"暂无"）、
 * {@link #normalizeTags}（标签 JSON 数组 trim/去空/去重）、{@link #normalizeDuration}
 * （推荐游玩时长→分钟数）。不可识别输入一律原样返回（时长返回 null），
 * 供 DG-1b 接入层写 norm 附加列使用。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
public final class AttractionFieldNormalizer {

    /** 营业时间段：HH:mm-HH:mm（时间段连接符兼容 - ~ — – 与两侧空白） */
    private static final Pattern OPEN_HOURS_SEGMENT =
            Pattern.compile("(\\d{1,2}:\\d{2})\\s*[-~—–]\\s*(\\d{1,2}:\\d{2})");
    /** 段间分隔符（规范化后统一为英文分号） */
    private static final String SEGMENT_SEPARATOR = "[;；,，、\\s]+";
    /** 时长：数值区间（小时）→ 取中值；其次小时+分钟组合、单值小时、单值分钟 */
    private static final Pattern DURATION_RANGE_HOURS =
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*[-~—–]\\s*(\\d+(?:\\.\\d+)?)\\s*小时");
    private static final Pattern DURATION_HOURS_MINUTES =
            Pattern.compile("(\\d+)\\s*小时\\s*(\\d+)\\s*分钟");
    private static final Pattern DURATION_HOURS =
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*小时");
    private static final Pattern DURATION_MINUTES =
            Pattern.compile("(\\d+)\\s*分钟");

    private AttractionFieldNormalizer() {
    }

    /**
     * 营业时间规范化：识别"HH:mm-HH:mm"段（支持多段与 ;；,，、 分隔，小时统一补零、
     * 连接符统一为 -、段间统一为 ;）、"全天"、"暂无"（原词返回）。
     * 整串含段以外说明文字、时间段越界（时>23/分>59）或无任何合法段时原样返回；
     * null → null，空白 → ""。
     */
    public static String normalizeOpenHours(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if ("全天".equals(trimmed) || "暂无".equals(trimmed)) {
            return trimmed;
        }
        Matcher matcher = OPEN_HOURS_SEGMENT.matcher(trimmed);
        StringBuilder normalized = new StringBuilder();
        int last = 0;
        int found = 0;
        while (matcher.find()) {
            String gap = trimmed.substring(last, matcher.start());
            if (!(gap.isBlank() || gap.matches(SEGMENT_SEPARATOR))) {
                return raw;
            }
            String open = matcher.group(1);
            String close = matcher.group(2);
            if (!isValidTime(open) || !isValidTime(close)) {
                return raw;
            }
            if (found > 0) {
                normalized.append(';');
            }
            normalized.append(canonicalTime(open)).append('-').append(canonicalTime(close));
            last = matcher.end();
            found++;
        }
        String tail = trimmed.substring(last);
        if (found == 0 || !(tail.isBlank() || tail.matches(SEGMENT_SEPARATOR))) {
            return raw;
        }
        return normalized.toString();
    }

    /**
     * 标签规范化：JSON 数组内元素 trim、去空串、去重（保序），输出仍为 JSON 数组字符串；
     * 非 JSON、非数组 JSON 或序列化失败时原样返回；null → null，空白 → ""；JSON null 元素跳过。
     */
    public static String normalizeTags(String rawJson) {
        if (rawJson == null) {
            return null;
        }
        String trimmed = rawJson.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        JsonNode root;
        try {
            root = JsonUtils.getMapper().readTree(trimmed);
        } catch (Exception e) {
            return rawJson;
        }
        if (!root.isArray()) {
            return rawJson;
        }
        Set<String> tags = new LinkedHashSet<>();
        for (JsonNode node : root) {
            if (node.isNull()) {
                continue;
            }
            String tag = node.asText().trim();
            if (!tag.isEmpty()) {
                tags.add(tag);
            }
        }
        try {
            return JsonUtils.getMapper().writeValueAsString(new ArrayList<>(tags));
        } catch (Exception e) {
            return rawJson;
        }
    }

    /**
     * 推荐游玩时长规范化（分钟）："3-4小时"→210（区间取中值）、"90分钟"→90、
     * "2小时"→120、"1.5小时"→90、"1小时30分钟"→90；含前后说明文字时可识别数值部分；
     * 不可识别返回 null；null → null，空白 → null。
     */
    public static Integer normalizeDuration(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        Matcher range = DURATION_RANGE_HOURS.matcher(trimmed);
        if (range.find()) {
            return toMinutes((Double.parseDouble(range.group(1)) + Double.parseDouble(range.group(2))) / 2.0);
        }
        Matcher hoursMinutes = DURATION_HOURS_MINUTES.matcher(trimmed);
        if (hoursMinutes.find()) {
            return Integer.parseInt(hoursMinutes.group(1)) * 60 + Integer.parseInt(hoursMinutes.group(2));
        }
        Matcher hours = DURATION_HOURS.matcher(trimmed);
        if (hours.find()) {
            return toMinutes(Double.parseDouble(hours.group(1)));
        }
        Matcher minutes = DURATION_MINUTES.matcher(trimmed);
        if (minutes.find()) {
            return Integer.parseInt(minutes.group(1));
        }
        return null;
    }

    private static Integer toMinutes(double hours) {
        return (int) Math.round(hours * 60.0);
    }

    private static boolean isValidTime(String hhmm) {
        int colon = hhmm.indexOf(':');
        int hour = Integer.parseInt(hhmm.substring(0, colon));
        int minute = Integer.parseInt(hhmm.substring(colon + 1));
        return hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59;
    }

    private static String canonicalTime(String hhmm) {
        int colon = hhmm.indexOf(':');
        return String.format("%02d:%s", Integer.parseInt(hhmm.substring(0, colon)), hhmm.substring(colon + 1));
    }
}
