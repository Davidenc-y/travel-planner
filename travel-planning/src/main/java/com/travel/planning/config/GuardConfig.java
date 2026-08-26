package com.travel.planning.config;

import com.travel.common.guard.RateLimitInterceptor;
import com.travel.core.guard.RateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MVC 侧防护装配（F90/F91）：RateLimiter + 限流拦截器。
 * 熔断注册表已下沉领域侧 {@code GuardDomainConfig}（M6-31），避免领域依赖 MVC。
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
}
