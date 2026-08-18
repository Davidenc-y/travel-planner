package com.travel.knowledge.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import com.travel.common.guard.RateLimitInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置（F87）：允许 travel-frontend（Next.js :3000）跨域调用 knowledge API。
 *
 * <p>仅开放用户面端点（attractions）；ETL / rag / memory 为后端集成与测试专用，
 * 前端不得直接调用（CORS 白名单与业务语义双重约束）。</p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    public WebConfig(RateLimitInterceptor rateLimitInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    // F87/F92：精确来源可配置；叠加 localhost:* 通配覆盖任意前端端口
    @Value("${travel.cors.allowed-origins:http://localhost:3000,http://127.0.0.1:3000,http://localhost:3100,http://127.0.0.1:3100}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/v1/attractions/**")
                .allowedOriginPatterns(mergeOrigins())
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    private String[] mergeOrigins() {
        String[] exact = allowedOrigins.split(",");
        String[] all = new String[exact.length + 2];
        System.arraycopy(exact, 0, all, 0, exact.length);
        all[exact.length] = "http://localhost:*";
        all[exact.length + 1] = "http://127.0.0.1:*";
        return all;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // F90：仅用户面 attractions 端点限流；ETL/RAG/memory 为后端集成，不在此限流
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/v1/attractions/**");
    }
}
