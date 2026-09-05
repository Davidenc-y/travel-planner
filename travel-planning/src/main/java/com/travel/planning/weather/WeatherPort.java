package com.travel.planning.weather;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * M15-1：外部天气端口。消费方只依赖接口；Open-Meteo / 未来实现可替换。
 */
public interface WeatherPort {

    /**
     * 查询指定日期的逐日天气。
     *
     * @param city  城市（如“成都”）
     * @param dates 需要天气的日期（有序、可跨天）
     * @return 与 dates 对齐的天气列表；不可解析/配额不足/关闭时 empty（上层降级）
     */
    Optional<List<DailyWeather>> forecastDates(String city, List<LocalDate> dates);
}
