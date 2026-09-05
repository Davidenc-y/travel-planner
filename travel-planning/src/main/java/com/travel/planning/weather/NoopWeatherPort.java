package com.travel.planning.weather;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** M15-1：weather.enabled=false 时空实现（回滚/灰度开关）。 */
@Component
@ConditionalOnProperty(prefix = "travel.weather", name = "enabled",
        havingValue = "false", matchIfMissing = true)
public class NoopWeatherPort implements WeatherPort {

    @Override
    public Optional<List<DailyWeather>> forecastDates(String city, List<LocalDate> dates) {
        return Optional.empty();
    }
}
