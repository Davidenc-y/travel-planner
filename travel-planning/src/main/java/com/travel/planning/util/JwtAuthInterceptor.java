package com.travel.planning.util;

import com.travel.common.auth.TokenAuthService;
import com.travel.webmvc.guard.RateLimitInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * JWT 认证拦截器（F68/B3-2）。
 *
 * <p>解析 {@code Authorization: Bearer <accessToken>}，校验通过后把 userId/username
 * 写入 {@link UserContextHolder}，控制器经 AuthUtils 仅认该上下文身份
 * （M16-1 起 X-User-Id 头/body/query 显式回退已全部移除，与 WebFlux 侧
 * JWT-only 语义对齐；Postman 用例请改带 Bearer token）。</p>
 *
 * <p>token 缺失/无效时不阻断请求（控制器兜底校验仍会抛 40101）；
 * 请求结束后 {@link #afterCompletion} 清理 ThreadLocal。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    // M6-25：JWT 逻辑下沉 travel-common，MVC 与未来 WebFlux 共用 TokenAuthService
    private final TokenAuthService tokenAuthService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith(BEARER_PREFIX)) {
            String token = auth.substring(BEARER_PREFIX.length()).trim();
            try {
                if (tokenAuthService.validateToken(token)) {
                    Long userId = tokenAuthService.getUserIdFromToken(token);
                    String username = tokenAuthService.getUsernameFromToken(token);
                    if (userId != null && userId > 0) {
                        UserContextHolder.setUserId(userId);
                        UserContextHolder.setUsername(username);
                        // M3-1：同步写请求属性，供限流/日志使用（防 X-User-Id 伪造）
                        request.setAttribute(RateLimitInterceptor.ATTR_USER_ID, userId);
                    }
                }
            } catch (Exception e) {
                log.warn("[Auth] accessToken 解析失败（回退显式 userId）: {}", e.getMessage());
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContextHolder.clear();
    }
}
