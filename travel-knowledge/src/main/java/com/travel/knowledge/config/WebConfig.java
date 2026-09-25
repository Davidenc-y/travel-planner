package com.travel.knowledge.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import com.travel.common.web.guard.RateLimitInterceptor;
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
    private final com.travel.knowledge.security.InternalTokenInterceptor internalTokenInterceptor;

    public WebConfig(RateLimitInterceptor rateLimitInterceptor,
                     com.travel.knowledge.security.InternalTokenInterceptor internalTokenInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.internalTokenInterceptor = internalTokenInterceptor;
    }

    // F87/F92：精确来源可配置；叠加 localhost:* 通配覆盖任意前端端口
    @Value("${travel.cors.allowed-origins:http://localhost:3000,http://127.0.0.1:3000,http://localhost:3100,http://127.0.0.1:3100}")
    private String allowedOrigins;

    // Y-2b：旧限流拦截器退役开关（显式登记现状值=true=注册，缺省=true 字节等价）；
    // 置 false=退役注册点（Bean 保留不阻断启动），限流交 SENTINEL_ENABLED 主闸开启后的
    // starter 内建 Web 埋点——双轨三态语义见方案 §一 Y-2b
    @Value("${travel.rate-limit.enabled:true}")
    private boolean rateLimitEnabled;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/v1/attractions/**")
                .allowedOriginPatterns(mergeOrigins())
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
        // F121：对象访问网关（图片 <img>/fetch 跨域）
        registry.addMapping("/api/v1/files/**")
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
        if (rateLimitEnabled) {
            registry.addInterceptor(rateLimitInterceptor)
                    .addPathPatterns("/api/v1/attractions/**");
        }
        // M21-3（SEC-02-04/05/07/08）：管理面端点服务间共享密钥（fail-closed）
        registry.addInterceptor(internalTokenInterceptor)
                .addPathPatterns("/api/v1/etl/**", "/api/v1/memory/**", "/api/v1/rag/**", "/api/v1/files/images");
    }
}
