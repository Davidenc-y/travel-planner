package com.travel.planning.stream;

import com.travel.core.stream.StreamMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * M6：流式指标（Micrometer；由框架统一埋点）。
 */
@Component
@RequiredArgsConstructor
public class MicrometerStreamMetrics implements StreamMetrics {

    private final MeterRegistry registry;

    @Override
    public void started(String domain) {
        Counter.builder("travel.stream.started")
                .tag("domain", domain)
                .register(registry)
                .increment();
    }

    @Override
    public void completed(String domain, long elapsedMs) {
        Counter.builder("travel.stream.completed")
                .tag("domain", domain)
                .register(registry)
                .increment();
        Timer.builder("travel.stream.duration")
                .tag("domain", domain)
                .register(registry)
                .record(Duration.ofMillis(elapsedMs));
    }

    @Override
    public void failed(String domain, Throwable error, long elapsedMs) {
        Counter.builder("travel.stream.failed")
                .tag("domain", domain)
                .tag("error", error == null ? "unknown" : error.getClass().getSimpleName())
                .register(registry)
                .increment();
    }
}
