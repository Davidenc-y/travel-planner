package com.travel.planning.util;

import com.travel.common.exception.BusinessException;

/**
 * 认证身份解析工具（F68/B3-2）。
 *
 * <p>统一解析当前请求的 userId：优先 {@link UserContextHolder}（由
 * {@link JwtAuthInterceptor} 从 accessToken 注入），其次显式参数（X-User-Id 头 /
 * body / query 兜底）；两者均缺失抛 40101（F52 防脏数据语义保持不变）。</p>
 */
public final class AuthUtils {

    private AuthUtils() {
    }

    public static Long resolveUserId(Long explicitUserId) {
        Long ctx = UserContextHolder.getUserIdOrNull();
        if (ctx != null && ctx > 0) {
            return ctx;
        }
        if (explicitUserId != null && explicitUserId > 0) {
            return explicitUserId;
        }
        throw new BusinessException(40101, "用户未登录");
    }
}
