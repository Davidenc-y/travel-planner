package com.travel.planning.util;

import com.travel.common.exception.BusinessException;

/**
 * 认证身份解析工具（F68/B3-2；M16-1 收敛 JWT 单源）。
 *
 * <p>统一解析当前请求的 userId：仅认 {@link UserContextHolder}（由
 * {@link JwtAuthInterceptor} 从 accessToken 注入）；缺失抛 40101。</p>
 *
 * <p>M16-1 起移除 X-User-Id 头 / body / query 显式身份回退（与 WebFlux 侧
 * ReactiveJwtAuthFilter 自 M6-57/T8 起的「JWT-only」语义对齐），杜绝可伪造
 * 身份通道。</p>
 */
public final class AuthUtils {

    private AuthUtils() {
    }

    public static Long resolveUserId() {
        Long ctx = UserContextHolder.getUserIdOrNull();
        if (ctx != null && ctx > 0) {
            return ctx;
        }
        throw new BusinessException(40101, "用户未登录");
    }
}
