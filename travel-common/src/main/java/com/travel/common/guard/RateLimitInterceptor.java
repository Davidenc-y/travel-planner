package com.travel.common.guard;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 请求限流拦截器（F90）。
 *
 * <p>按 userId + 接口维度限流（消费 travel.rate-limit.per-minute 休眠配置）；
 * 超限返回 HTTP 429。放行逻辑与鉴权无关，仅做频率保护。</p>
 */
@Slf4j
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter limiter;

    public RateLimitInterceptor(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String userId = resolveUserId(request);
        String key = userId + ":" + request.getRequestURI();
        if (!limiter.tryAcquire(key)) {
            log.warn("[RateLimit] 请求超限被拦截: key={}", key);
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":40301,\"message\":\"请求过于频繁，请稍后重试\"}");
            return false;
        }
        return true;
    }

    private String resolveUserId(HttpServletRequest request) {
        String userId = request.getHeader("X-User-Id");
        if (userId == null || userId.isBlank()) {
            userId = "anonymous";
        }
        return userId;
    }
}
