package com.travel.planning.service;

import com.travel.planning.agent.support.ChatWeatherContextPort;
import com.travel.planning.weather.WeatherContextBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * M15-1：聊天规划天气上下文实现（planning 装配，chat-domain 只依赖接口）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWeatherContextPortImpl implements ChatWeatherContextPort {

    private final WeatherContextBuilder weatherContextBuilder;
    // M18-2：目的地/天数解析经 ItineraryWritebackProperties（与行程回写共用词表）
    private final ItineraryWritebackProperties parseProps;

    @Override
    public String build(String composed) {
        if (composed == null || composed.isBlank()) {
            return "";
        }
        String destination = parseProps.parseDestination(composed);
        Integer days = parseProps.parseDays(composed);
        if (destination == null || days == null || days <= 0) {
            return "";
        }
        String context = weatherContextBuilder.build(destination, null, days);
        if (context == null) {
            return "";
        }
        log.info("[ChatWeather] 聊天规划天气注入: destination={}, days={}", destination, days);
        return context;
    }
}
