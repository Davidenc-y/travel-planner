package com.travel.planning.config;

import com.travel.common.guard.CircuitBreaker;
import com.travel.common.guard.RateLimiter;
import com.travel.common.guard.RateLimitInterceptor;
import com.travel.planning.guard.GuardProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 安全防护装配（F90/F91）：RateLimiter（消费 travel.rate-limit.per-minute 休眠配置）、
 * 限流拦截器、熔断注册表、注入规则关键词刷新。
 */
@Configuration
@RequiredArgsConstructor
public class GuardConfig {

    @Value("${travel.rate-limit.per-minute:100}")
    private int perMinute;

    @Bean
    public RateLimiter rateLimiter() {
        return new RateLimiter(perMinute);
    }

    @Bean
    public RateLimitInterceptor rateLimitInterceptor(RateLimiter rateLimiter) {
        return new RateLimitInterceptor(rateLimiter);
    }

    @Bean
    public CircuitBreaker.Registry circuitBreakerRegistry(GuardProperties properties) {
        GuardProperties.CircuitBreaker cb = properties.getCircuitBreaker();
        return new CircuitBreaker.Registry(cb.getFailureThreshold(), cb.getWindowMs(), cb.getOpenTimeoutMs());
    }

}
