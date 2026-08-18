package com.travel.knowledge.config;

import com.travel.common.guard.RateLimiter;
import com.travel.common.guard.RateLimitInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** knowledge 侧限流装配（F90，复用 travel-common RateLimiter） */
@Configuration
public class GuardConfig {

    @Value("${travel.rate-limit.per-minute:100}")
    private int perMinute;

    @Bean
    public RateLimiter knowledgeRateLimiter() {
        return new RateLimiter(perMinute);
    }

    @Bean
    public RateLimitInterceptor knowledgeRateLimitInterceptor(RateLimiter knowledgeRateLimiter) {
        return new RateLimitInterceptor(knowledgeRateLimiter);
    }
}
