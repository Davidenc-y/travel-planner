package com.travel.planning.config;

import com.travel.planning.util.JwtAuthInterceptor;
import com.travel.common.guard.RateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置（F68/B3-2）：注册 JWT 认证拦截器。
 *
 * <p>对 /api/** 生效，公开端点（注册/登录/刷新 Token）排除；
 * 拦截器只负责"有 token 则注入身份"，不强制阻断（兼容 X-User-Id 兜底）。</p>
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final JwtAuthInterceptor jwtAuthInterceptor;
    // F90：请求限流（按 userId+接口，消费 travel.rate-limit.per-minute）
    private final RateLimitInterceptor rateLimitInterceptor;

    /**
     * F87/F92：前端跨域白名单（travel-frontend Next.js）。
     * 逗号分隔精确 origin；并始终叠加 http://localhost:* 与 http://127.0.0.1:*
     * 通配（开发任意端口，如 3000/3100），杜绝"前端换端口 → 登录 Net Error"。
     */
    @Value("${travel.cors.allowed-origins:http://localhost:3000,http://127.0.0.1:3000,http://localhost:3100,http://127.0.0.1:3100}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(mergeOrigins())
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
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
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/**");
        registry.addInterceptor(jwtAuthInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/v1/auth/register",
                        "/api/v1/auth/login",
                        "/api/v1/auth/refresh");
    }
}
