package com.travel.planning.config;

import com.travel.core.guard.CircuitBreaker;
import com.travel.planning.guard.GuardProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M6-31：领域侧防护装配（熔断注册表）。
 *
 * <p>原 GuardConfig 因 RateLimitInterceptor（travel-web-mvc）留在 MVC 侧；本配置
 * 提供纯领域 Bean（CircuitBreaker.Registry），供 travel-planning 与未来
 * travel-stream-webflux 共用，避免领域模块反向依赖 MVC。</p>
 */
@Configuration
public class GuardDomainConfig {

    @Bean
    public CircuitBreaker.Registry circuitBreakerRegistry(GuardProperties properties) {
        GuardProperties.CircuitBreaker cb = properties.getCircuitBreaker();
        return new CircuitBreaker.Registry(cb.getFailureThreshold(), cb.getWindowMs(),
                cb.getOpenTimeoutMs());
    }
}
