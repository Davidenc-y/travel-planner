package com.travel.planning.util;

import com.travel.common.auth.TokenAuthService;
import com.travel.common.exception.BusinessException;
import com.travel.common.web.guard.RateLimitInterceptor;
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
    // M21-4：登出吊销黑名单（jti 级，Redis TTL=剩余有效期）
    private final AccessTokenBlacklistService blacklistService;
    /**
     * M22-1（Phase 3A，D-V8-1）：路径级认证强制模式——
     * observe（默认）：解析失败仅记录 would-deny WARN，不阻断（灰度观测）；
     * on：非豁免路径（WebConfig excludePathPatterns）未认证一律 40101，结构性杜绝"逐点调用遗漏"复发；
     * off：回退纯解析语义（等价 M21-4 前行为，回滚开关）。
     */
    @org.springframework.beans.factory.annotation.Value("${travel.auth.enforce-mode:observe}")
    private String enforceMode;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // M25 补缺：CORS 预检（OPTIONS）按规范不携带 Authorization 等自定义头，与认证无关——
        // 放行交由 CORS 处理器响应；否则 enforce=on 会拦截预检，浏览器所有跨域请求
        // 表现为 Network Error（2026-09-06 实测回归，行程页/模型列表全中）
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        boolean authenticated = false;
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith(BEARER_PREFIX)) {
            String token = auth.substring(BEARER_PREFIX.length()).trim();
            try {
                // M21-4：只接受 access 类型（refresh 冒充 access 被拒）；已登出吊销的 jti 拒绝
                if (tokenAuthService.validateAccessToken(token)
                        && !blacklistService.isRevoked(tokenAuthService.getJti(token))) {
                    Long userId = tokenAuthService.getUserIdFromToken(token);
                    String username = tokenAuthService.getUsernameFromToken(token);
                    if (userId != null && userId > 0) {
                        UserContextHolder.setUserId(userId);
                        UserContextHolder.setUsername(username);
                        // M3-1：同步写请求属性，供限流/日志使用（防 X-User-Id 伪造）
                        request.setAttribute(RateLimitInterceptor.ATTR_USER_ID, userId);
                        authenticated = true;
                    }
                }
            } catch (Exception e) {
                log.warn("[Auth] accessToken 解析失败（回退显式 userId）: {}", e.getMessage());
            }
        }
        // M22-1：路径级强制（"默认拒绝+显式豁免"——豁免由 WebConfig excludePathPatterns 框架层完成）
        if (!authenticated) {
            // null 兜底=observe（未走 Spring 装配的构造路径/未配置时保持观测态，不阻断）
            switch (enforceMode == null ? "observe" : enforceMode) {
                case "on" -> throw new BusinessException(40101, "用户未登录");
                case "observe" -> log.warn("[Auth][observe] would-deny: {} {}",
                        request.getMethod(), request.getRequestURI());
                default -> { /* off：保持纯解析语义 */ }
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
