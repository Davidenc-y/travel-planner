package com.travel.webflux.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * M6-30：WebFlux CORS（镜像 MVC WebConfig 语义）。
 *
 * <p>逗号分隔精确 origin + 恒叠加 http://localhost:* 与 http://127.0.0.1:*
 * 通配，保证前端 3000/3100 任意开发端口可用。</p>
 */
@Configuration
public class WebFluxCorsConfig implements WebFluxConfigurer {

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
}
