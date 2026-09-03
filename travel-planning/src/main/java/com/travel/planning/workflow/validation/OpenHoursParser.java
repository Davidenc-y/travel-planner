package com.travel.planning.workflow.validation;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M8-3：开放时间宽松解析器（t_attraction.open_hours 常见格式）。
 *
 * <p>支持的格式：</p>
 * <ul>
 *   <li>{@code "09:00-17:00"} / {@code "08:30-17:30"} → LocalTime 区间；</li>
 *   <li>{@code "周一至周日 08:00-18:00"} 等带星期前缀 → 提取区间；</li>
 *   <li>{@code "全天开放"} / {@code "全天"} → 00:00-23:59；</li>
 *   <li>{@code "旺季08:00-18:00,淡季09:00-17:00"} 等多区间 → 取最大区间（保守）；</li>
 *   <li>{@code "8:00-17:00"} 单数字小时 → 兼容解析。</li>
 * </ul>
 *
 * <p>解析失败 → {@link Optional#empty()}（视为无约束，规则跳过——确定性降级不产生误报）。</p>
 */
public final class OpenHoursParser {

    /** 区间正则：HH:mm 或 H:mm（容忍空格/中文分隔符） */
    private static final Pattern RANGE = Pattern.compile(
            "([01]?\\d|2[0-3]):?([0-5]\\d)?\\s*[-—至~到]\\s*([01]?\\d|2[0-3]):?([0-5]\\d)?");

    private OpenHoursParser() {
    }

    /** 开放时间区间（含端点） */
    public record Hours(LocalTime open, LocalTime close) {
    }

    /**
     * 宽松解析开放时间；失败返回 {@link Optional#empty()}（视为无约束）。
     */
    public static Optional<Hours> parse(String openHours) {
        if (openHours == null || openHours.isBlank()) {
            return Optional.empty();
        }
        String text = openHours.trim();
        if (text.contains("全天")) {
            return Optional.of(new Hours(LocalTime.MIN, LocalTime.of(23, 59)));
        }
        Matcher matcher = RANGE.matcher(text);
        List<Hours> ranges = new ArrayList<>();
        while (matcher.find()) {
            LocalTime open = toTime(matcher.group(1), matcher.group(2));
            LocalTime close = toTime(matcher.group(3), matcher.group(4));
            if (open == null || close == null) {
                continue;
            }
            ranges.add(new Hours(open, close));
        }
        if (ranges.isEmpty()) {
            return Optional.empty();
        }
        // 多区间（旺季/淡季）取最大区间：跨 midnight 时用跨度排序
        return ranges.stream().max((a, b) -> Long.compare(span(a), span(b)));
    }

    /**
     * 解析 timeSlot（"09:00-12:00"）；失败返回 {@link Optional#empty()}。
     */
    public static Optional<Hours> parseSlot(String timeSlot) {
        if (timeSlot == null || timeSlot.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = RANGE.matcher(timeSlot.trim());
        if (!matcher.find()) {
            return Optional.empty();
        }
        LocalTime open = toTime(matcher.group(1), matcher.group(2));
        LocalTime close = toTime(matcher.group(3), matcher.group(4));
        if (open == null || close == null) {
            return Optional.empty();
        }
        return Optional.of(new Hours(open, close));
    }

    private static long span(Hours h) {
        long open = h.open().toSecondOfDay();
        long close = h.close().toSecondOfDay();
        return close >= open ? close - open : (24 * 3600L) - open + close;
    }

    private static LocalTime toTime(String hour, String minute) {
        if (hour == null) {
            return null;
        }
        try {
            int h = Integer.parseInt(hour);
            int m = minute == null || minute.isBlank() ? 0 : Integer.parseInt(minute);
            if (h < 0 || h > 23 || m < 0 || m > 59) {
                return null;
            }
            return LocalTime.of(h, m);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
