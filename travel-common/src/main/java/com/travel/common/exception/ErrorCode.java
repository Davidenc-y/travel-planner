package com.travel.common.exception;

/**
 * M3-6：统一错误码枚举（code + HTTP 状态 + 默认文案），消除魔法数字散落。
 * 业务码与 HTTP 状态语义对齐由 GlobalExceptionHandler 按配置启用。
 */
public enum ErrorCode {

    OK(200, 200, "success"),
    BAD_REQUEST(40001, 400, "请求参数错误"),
    PARAM_INVALID(40002, 400, "参数非法"),
    UNAUTHORIZED(40101, 401, "用户未登录"),
    RATE_LIMITED(40301, 429, "请求过于频繁"),
    FORBIDDEN(40302, 403, "请求被拒绝"),
    NOT_FOUND(40401, 404, "资源不存在"),
    NOT_FOUND_ITEM(40402, 404, "条目不存在"),
    SYSTEM_ERROR(50000, 500, "系统异常"),
    ITINERARY_ERROR(50001, 500, "行程生成失败"),
    RAG_ERROR(50002, 500, "检索失败"),
    EXTERNAL_API(50301, 503, "外部服务异常"),
    /** M3-22：画像乐观锁冲突重试耗尽（跨实例写冲突） */
    PROFILE_CONFLICT(40901, 409, "画像更新冲突，请稍后重试");

    private final int code;
    private final int httpStatus;
    private final String message;

    ErrorCode(int code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String message() {
        return message;
    }

    public static ErrorCode of(int code) {
        for (ErrorCode e : values()) {
            if (e.code == code) {
                return e;
            }
        }
        return SYSTEM_ERROR;
    }
}
