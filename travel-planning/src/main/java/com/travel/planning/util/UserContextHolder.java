package com.travel.planning.util;

import com.travel.common.exception.BusinessException;

/**
 * 用户上下文持有器
 *
 * <p>基于 ThreadLocal 存储当前请求的用户 ID，
 * 供 Service 层获取当前登录用户（无需每个方法传 userId 参数）。</p>
 *
 * <p>使用方式：</p>
 * <pre>
 * // 在 Filter/Interceptor 中设置
 * UserContextHolder.setUserId(userId);
 *
 * // 在 Service 中获取
 * Long userId = UserContextHolder.getUserId();
 *
 * // 请求结束时清理
 * UserContextHolder.clear();
 * </pre>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
public final class UserContextHolder {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USERNAME = new ThreadLocal<>();

    private UserContextHolder() {}

    /**
     * 设置用户 ID
     */
    public static void setUserId(Long userId) {
        USER_ID.set(userId);
    }

    /**
     * 获取用户 ID（未设置时抛出异常）
     */
    public static Long getUserId() {
        Long userId = USER_ID.get();
        if (userId == null) {
            throw new BusinessException(40101, "用户未登录");
        }
        return userId;
    }

    /**
     * 获取用户 ID（未设置时返回 null，不抛异常）
     */
    public static Long getUserIdOrNull() {
        return USER_ID.get();
    }

    /**
     * 设置用户名
     */
    public static void setUsername(String username) {
        USERNAME.set(username);
    }

    /**
     * 获取用户名
     */
    public static String getUsername() {
        return USERNAME.get();
    }

    /**
     * 清理 ThreadLocal（必须在请求结束时调用，防止内存泄漏）
     */
    public static void clear() {
        USER_ID.remove();
        USERNAME.remove();
    }
}
