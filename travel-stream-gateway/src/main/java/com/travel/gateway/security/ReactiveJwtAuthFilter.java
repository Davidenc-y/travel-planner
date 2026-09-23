package com.travel.gateway.security;

import com.travel.common.auth.TokenAuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;

/**
 * V-3b：网关 JWT 认证过滤器（原 travel-stream-webflux ReactiveJwtAuthFilter 适配）。
 *
 * <p>与旧实现的语义差异（02 方案 V-3b：网关是边界）：旧过滤器不阻断（缺/无效 token
 * 放行，由控制器抛 40101）；网关必须在入口前置拒绝——无 token/无效/过期→401，
 * 白名单 {@code /actuator/**} 放行（探活端点不携带用户凭证）。</p>
 *
 * <p>{@code validateAccessToken} 为同步 CPU 型 JWT 解析，经 {@code boundedElastic}
 * 包裹避免占用 Netty EventLoop（02 方案风险表"网关 JWT reactive 包装阻塞"缓解项）；
 * 校验通过后 userId 写 exchange attribute（非 ThreadLocal，符合响应式模型，沿旧实现）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReactiveJwtAuthFilter implements WebFilter {

    public static final String ATTR_USER_ID = ReactiveJwtAuthFilter.class.getName() + ".userId";

    private static final String BEARER_PREFIX = "Bearer ";

    private static final String ACTUATOR_PREFIX = "/actuator/";

    private final TokenAuthService tokenAuthService;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.equals("/actuator") || path.startsWith(ACTUATOR_PREFIX)) {
            return chain.filter(exchange);
        }
        String auth = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith(BEARER_PREFIX)) {
            return unauthorized(exchange, "missing bearer token");
        }
        String token = auth.substring(BEARER_PREFIX.length()).trim();
        return Mono.fromCallable(() -> {
                    // M21-4：只接受 access 类型（与旧实现一致）；-1=无效/过期哨兵——
                    // fromCallable 返回 null 即空 Mono，flatMap 会被整体跳过（静默放行），禁用 null
                    return tokenAuthService.validateAccessToken(token)
                            ? tokenAuthService.getUserIdFromToken(token) : -1L;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(userId -> {
                    if (userId == null || userId <= 0) {
                        return unauthorized(exchange, "invalid or expired token");
                    }
                    exchange.getAttributes().put(ATTR_USER_ID, userId);
                    return chain.filter(exchange);
                })
                .onErrorResume(e -> {
                    log.warn("[GatewayAuth] token 校验异常: {}", e.getMessage());
                    return unauthorized(exchange, "token validation error");
                });
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String reason) {
        log.info("[GatewayAuth] 401: path={}, reason={}", exchange.getRequest().getPath(), reason);
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"code\":401,\"message\":\"" + reason + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }
}
