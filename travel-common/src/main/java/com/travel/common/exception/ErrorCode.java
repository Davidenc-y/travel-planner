package com.travel.common.exception;

/**
 * M3-6：统一错误码枚举（code + HTTP 状态 + 默认文案），消除魔法数字散落。
 * 业务码与 HTTP 状态语义对齐由 GlobalExceptionHandler 按配置启用。
 */
public enum ErrorCode {

    OK(200, 200, "success"),
    BAD_REQUEST(40001, 400, "请求参数错误"),
    PARAM_INVALID(40002, 400, "参数非法"),
    USERNAME_EXISTS(40003, 409, "用户名已存在"),
    /** M5-1：邮箱已被其他账号绑定 */
    EMAIL_EXISTS(40004, 409, "邮箱已被绑定"),
    /** M7：请求携带的模型未注册/未启用/不可选（入口快速失败，不静默回退） */
    MODEL_NOT_FOUND(40005, 400, "模型不存在或不可用"),
    /** M8-9h：模型额度不足（DashScope 403 Free quota exhausted 等） */
    MODEL_QUOTA_EXCEEDED(40303, 402, "模型额度不足，请切换其他模型，或在控制台充值/关闭“仅免费额度”后重试"),
    /** M10-2c：模型维度熔断 OPEN（连续失败后暂不可用，可稍后/切换模型） */
    MODEL_CIRCUIT_OPEN(40304, 503, "模型暂不可用，请稍后重试或切换其他模型"),
    UNAUTHORIZED(40101, 401, "用户未登录"),
    AUTH_TOKEN_INVALID(40102, 401, "refreshToken 无效或已过期"),
    AUTH_TOKEN_EXPIRED(40103, 401, "refreshToken 已失效，请重新登录"),
    RATE_LIMITED(40301, 429, "请求过于频繁"),
    /** S-A3/A-5（P1-②）：模型不可用/无权限（DashScope 403 且错误体无额度码——原 FORBIDDEN 通用文案零使用，按方案改造） */
    MODEL_UNAVAILABLE(40302, 403, "该模型当前不可用，请切换模型"),
    NOT_FOUND(40401, 404, "资源不存在"),
    NOT_FOUND_ITEM(40402, 404, "条目不存在"),
    SESSION_NOT_FOUND(40404, 404, "会话不存在"),
    SYSTEM_ERROR(50000, 500, "系统异常"),
    ITINERARY_ERROR(50001, 500, "行程生成失败"),
    RAG_ERROR(50002, 500, "检索失败"),
    EXTERNAL_API(50301, 503, "外部服务异常"),
    /** M3-22：画像乐观锁冲突重试耗尽（跨实例写冲突） */
    PROFILE_CONFLICT(40901, 409, "画像更新冲突，请稍后重试"),
    /** M4-4：会话已关闭（ARCHIVED）后发送消息 */
    SESSION_CLOSED(40902, 409, "会话已关闭"),
    /** M4-9：行程当前状态不支持继续生成（已 GENERATED） */
    ITINERARY_NOT_RESUMABLE(40903, 409, "行程当前状态不支持继续生成"),
    /** M4-3：同幂等键请求仍在处理中（客户端同键退避重试） */
    MESSAGE_PROCESSING(40904, 409, "消息处理中，请稍后重试"),
    /** M4-8：行程仍在生成中（同 clientRequestId 并发请求应等待或走 resume） */
    ITINERARY_PROCESSING(40905, 409, "行程正在生成中，请稍后重试或使用继续生成"),
    /** MM-8-fix（二次审批）：方法级开关未启用（如灰度动态写默认关闭）——ResponseStatusException
     *  不被 GlobalExceptionHandler 识别会吞成 50000，改走 BusinessException 通道后 HTTP 405 可达客户端 */
    METHOD_NOT_ALLOWED(40501, 405, "方法不被允许（功能开关未启用）"),

    // ==================== W-4 补缺（码+消息逐字取自 ChatService 既有字面量；同码异文案=既有码位复用实证，禁漂移=P0⑧） ====================
    /** ChatService 幂等键校验（40001 码位与 BAD_REQUEST 复用，文案逐字保留） */
    IDEMPOTENCY_KEY_REQUIRED(40001, 400, "幂等键不能为空"),
    /** ChatService 会话关闭冲突（40902 码位与 SESSION_CLOSED 复用，文案逐字保留） */
    SESSION_STATE_CONFLICT(40902, 409, "会话状态冲突，请稍后重试"),
    /** ChatService 会话归属拒绝（40302 码位与 MODEL_UNAVAILABLE 复用，文案逐字保留） */
    SESSION_ACCESS_DENIED(40302, 403, "无权访问该会话");

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
