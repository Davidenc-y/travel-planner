package com.travel.knowledge.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置（F87）：允许 travel-frontend（Next.js :3000）跨域调用 knowledge API。
 *
 * <p>仅开放用户面端点（attractions）；ETL / rag / memory 为后端集成与测试专用，
 * 前端不得直接调用（CORS 白名单与业务语义双重约束）。</p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${travel.cors.allowed-origins:http://localhost:3000,http://127.0.0.1:3000}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/v1/attractions/**")
                .allowedOrigins(allowedOrigins.split(","))
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
