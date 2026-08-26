package com.travel.webflux.security;

import com.travel.common.auth.TokenAuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * M6-30：WebFlux 认证过滤器（对应 MVC {@code JwtAuthInterceptor}）。
 *
 * <p>解析 {@code Authorization: Bearer <accessToken>}，校验通过后把 userId 写入
 * exchange attribute（非 ThreadLocal，符合响应式模型）；token 缺失/无效不阻断
 * 请求，由控制器回退 X-User-Id 或抛 40101（与 MVC 双通道语义一致）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReactiveJwtAuthFilter implements WebFilter {

    public static final String ATTR_USER_ID = ReactiveJwtAuthFilter.class.getName() + ".userId";

    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenAuthService tokenAuthService;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String auth = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith(BEARER_PREFIX)) {
            String token = auth.substring(BEARER_PREFIX.length()).trim();
            try {
                if (tokenAuthService.validateToken(token)) {
                    Long userId = tokenAuthService.getUserIdFromToken(token);
                    if (userId != null && userId > 0) {
                        exchange.getAttributes().put(ATTR_USER_ID, userId);
                    }
                }
            } catch (Exception e) {
                log.warn("[WebFluxAuth] accessToken 解析失败（回退显式 userId）: {}", e.getMessage());
            }
        }
        return chain.filter(exchange);
    }
}
