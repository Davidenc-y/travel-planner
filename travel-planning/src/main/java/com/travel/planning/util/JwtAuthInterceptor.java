package com.travel.planning.util;

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
 * 写入 {@link UserContextHolder}；控制器优先使用上下文身份，X-User-Id 头降级为兜底
 * （向后兼容既有 Postman 用例），从而逐步消除 X-User-Id 头依赖（F52 增强）。</p>
 *
 * <p>token 缺失/无效时不阻断请求（控制器兜底校验仍会抛 40101），保证迁移期
 * 双通道共存；请求结束后 {@link #afterCompletion} 清理 ThreadLocal。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith(BEARER_PREFIX)) {
            String token = auth.substring(BEARER_PREFIX.length()).trim();
            try {
                if (jwtUtil.validateToken(token)) {
                    Long userId = jwtUtil.getUserIdFromToken(token);
                    String username = jwtUtil.getUsernameFromToken(token);
                    if (userId != null && userId > 0) {
                        UserContextHolder.setUserId(userId);
                        UserContextHolder.setUsername(username);
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
