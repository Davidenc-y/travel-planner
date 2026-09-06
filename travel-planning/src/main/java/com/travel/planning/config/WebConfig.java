package com.travel.planning.config;

import com.travel.planning.util.JwtAuthInterceptor;
import com.travel.common.web.guard.RateLimitInterceptor;
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
 * 拦截器只负责"有 token 则注入身份"，不强制阻断（控制器 AuthUtils 兜底校验
 * 抛 40101；M16-1 起身份仅认 accessToken，无显式回退通道）。</p>
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
                        "/api/v1/auth/refresh",
                        // M22-1：进程间回写桥走 X-Internal-Token（M21-2 fail-closed），不走用户 Bearer
                        "/api/v1/itineraries/chat-writeback",
                        // M25（E5）：分享公开只读面（授权=签名 token；匿名限流面覆盖）
                        "/api/v1/share/**");
    }
}
