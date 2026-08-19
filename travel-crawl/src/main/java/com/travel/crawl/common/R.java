package com.travel.crawl.common;

/**
 * 轻量统一响应（travel-crawl 保持不依赖 travel-common，镜像 common.result.R 契约）。
 */
public record R<T>(int code, String message, T data, long timestamp) {

    public static <T> R<T> ok(T data) {
        return new R<>(200, "success", data, System.currentTimeMillis());
    }

    public static <T> R<T> fail(int code, String message) {
        return new R<>(code, message, null, System.currentTimeMillis());
    }
}
