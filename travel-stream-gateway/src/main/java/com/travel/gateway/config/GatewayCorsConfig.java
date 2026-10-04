package com.travel.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * AW-1：网关 CORS 配置——前端（localhost:3000）直连网关（8083）SSE/prewrite 为跨域，
 * 预检 OPTIONS 无 Authorization 头被 ReactiveJwtAuthFilter 401 拒绝（2026-10-03 实测：
 * 规划成功但锚定/偏好回写链全断）。本过滤器置最高优先级：预检直接应答、实际请求补
 * CORS 响应头；allowedOriginPatterns 限本机开发源，生产同源不受影响。
 */
@Configuration
public class GatewayCorsConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public CorsWebFilter gatewayCorsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("http://localhost:*", "http://127.0.0.1:*"));
        config.addAllowedMethod("*");
        config.addAllowedHeader("*");
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }
}
