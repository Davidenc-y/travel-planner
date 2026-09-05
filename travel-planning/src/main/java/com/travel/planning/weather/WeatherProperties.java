package com.travel.planning.weather;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * M15-1：Open-Meteo 天气接入配置（零 Key）。
 *
 * <p>{@code travel.weather.enabled=false}（默认）时装配 NoopWeatherPort，
 * 行程生成/地图链路零行为变化。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.weather")
public class WeatherProperties {

    /** 灰度总开关（默认 false，行为等价红线） */
    private boolean enabled = false;

    private String forecastBaseUrl = "https://api.open-meteo.com/v1";
    private String geocodingBaseUrl = "https://geocoding-api.open-meteo.com/v1";

    private int timeoutMs = 8000;
    private int connectTimeoutMs = 5000;

    /** 内部保守日预算（Open-Meteo 免费 10000/日） */
    private int dayQuota = 3000;
    private int maxQps = 1;

    private boolean cacheEnabled = true;
    private int forecastCacheTtlHours = 24;
    private int geocodeCacheTtlDays = 30;

    private int circuitFailureThreshold = 3;
    private int circuitWindowMs = 60_000;
    private int circuitOpenMs = 30_000;
}
