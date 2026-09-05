package com.travel.planning.weather;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * M15-1：出行日期天气 → 上下文文本（供行程生成/续跑用户消息注入）。
 * 关闭/无结果返回空串，保持行为等价。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherContextBuilder {

    private final WeatherPort weatherPort;
    private final WeatherProperties props;

    /** startDate 可空（空则从明天起顺延）；days<=0 返回空串。 */
    public String build(String destination, String startDate, int days) {
        if (!props.isEnabled() || destination == null || destination.isBlank() || days <= 0) {
            return "";
        }
        LocalDate first = parseStart(startDate);
        List<LocalDate> dates = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            dates.add(first.plusDays(i));
        }
        try {
            List<DailyWeather> list = weatherPort.forecastDates(destination, dates).orElse(List.of());
            if (list.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder("【出行天气参考】");
            for (int i = 0; i < list.size(); i++) {
                DailyWeather w = list.get(i);
                sb.append("\n- D").append(i + 1).append(" ").append(w.date())
                        .append("：").append(w.weatherText() == null ? "未知" : w.weatherText());
                if (w.tempMin() != null || w.tempMax() != null) {
                    sb.append("，").append(fmt(w.tempMin())).append("~")
                            .append(fmt(w.tempMax())).append("℃");
                }
            }
            log.info("[WeatherContext] 行程天气注入: destination={}, days={}", destination, days);
            return sb.toString();
        } catch (Exception e) {
            log.warn("[WeatherContext] 构建失败（降级为空）: {}", e.getMessage());
            return "";
        }
    }

    static LocalDate parseStart(String startDate) {
        if (startDate != null && !startDate.isBlank()) {
            try {
                return LocalDate.parse(startDate.trim());
            } catch (DateTimeParseException ignored) {
                // 非法日期回退明天
            }
        }
        return LocalDate.now().plusDays(1);
    }

    private static String fmt(Double v) {
        if (v == null) {
            return "-";
        }
        return v == Math.floor(v) ? String.valueOf(v.intValue()) : String.format("%.1f", v);
    }
}
