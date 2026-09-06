package com.travel.common.web.guard;

import com.travel.common.util.JsonUtils;
import com.travel.common.result.R;
import com.travel.common.exception.ErrorCode;
import com.travel.core.guard.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 请求限流拦截器（F90；M6-9 P2 自 travel-common 迁移，行为不变）。
 *
 * <p>按 userId + 接口维度限流（消费 travel.rate-limit.per-minute 休眠配置）；
 * 超限返回 HTTP 429。放行逻辑与鉴权无关，仅做频率保护。</p>
 */
@Slf4j
public class RateLimitInterceptor implements HandlerInterceptor {

    /** M3-1：JWT 拦截器写入的请求级 userId（M16-1 起为唯一身份源，无头回退） */
    public static final String ATTR_USER_ID = "travel.userId";

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
            // M16-2：错误码与文案单源于 ErrorCode.RATE_LIMITED（消除与枚举的双源漂移）
            response.getWriter().write(JsonUtils.toJson(
                    R.fail(ErrorCode.RATE_LIMITED.code(), ErrorCode.RATE_LIMITED.message())));
            return false;
        }
        return true;
    }

    private String resolveUserId(HttpServletRequest request) {
        Object attr = request.getAttribute(ATTR_USER_ID);
        if (attr != null && !attr.toString().isBlank()) {
            return attr.toString();
        }
        // M16-1：不再采信可伪造的 X-User-Id 头（与鉴权 JWT 单源对齐）；无身份请求共享 anonymous 桶
        return "anonymous";
    }
}
