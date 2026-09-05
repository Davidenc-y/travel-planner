package com.travel.planning.weather;

/**
 * M15-1：单日天气（Open-Meteo 精简输出）。
 *
 * @param date ISO 日期（yyyy-MM-dd）
 * @param weatherCode WMO 天气代码
 * @param weatherText 中文文案（晴/多云/雨/雪/雷阵雨…）
 * @param tempMax 最高温 ℃
 * @param tempMin 最低温 ℃
 */
public record DailyWeather(
        String date,
        Integer weatherCode,
        String weatherText,
        Double tempMax,
        Double tempMin) {
}
