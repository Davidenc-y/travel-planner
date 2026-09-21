package com.travel.planning.service;

import com.travel.common.exception.BusinessException;
import com.travel.common.exception.ErrorCode;
import com.travel.planning.memory.pipeline.ChatRoutingStep;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * M8-9i：模型额度不足异常识别与上抛（DashScope OpenAI 兼容端点
 * 403 + "Free quota exhausted"等）。
 *
 * <p>被 {@link ChatRoutingStep} 与 {@link ChatService} 共用：
 * 路由步骤负责"吞异常转兜底文案"前先把额度类异常上抛为业务异常 40303，
 * ChatService 负责补充模型名后沿 SSE/JSON 链路透传，避免前端只看到
 * "Agent 流式调用失败"或原始 403 而没有任何明确提示。</p>
 *
 * <p>S-A3/A-5（P1-②）语义区分：额度类（403+quota／429／Throttling）→40303；
 * 403 且错误体无任何额度码（模型未授权/不可用）→40302 {@link ErrorCode#MODEL_UNAVAILABLE}
 * （文案"该模型当前不可用，请切换模型"），不再吞成兜底文案。</p>
 */
public final class ModelQuotaExceptionSupport {

    /** S 审计修复（T1 实弹）：直答流路径的 403 包装在 NonTransientAiException（body 在 message），
     *  非 RestClient/WebClientResponseException——两 walker 原样漏识别 → 用户见通用兜底而非 40303/40302。 */
    private static boolean aiExClassifies(Throwable cur, boolean quotaMode) {
        if (!(cur instanceof org.springframework.ai.retry.NonTransientAiException nae)) {
            return false;
        }
        String m = String.valueOf(nae.getMessage());
        if (!m.startsWith("403")) {
            return false;
        }
        return quotaMode ? isQuotaLike(403, m) : !isQuotaLike(403, m);
    }

    private ModelQuotaExceptionSupport() {
    }

    /**
     * 沿 cause 链（最多 16 层）识别模型额度不足异常。
     *
     * <p>Spring 6.2 中 RestClient 与 WebClient 的响应异常并非同一继承树，
     * 需要分别识别；同时兼容已被 {@link BusinessException} 包装的情形
     * （cause 链仍可命中原始 HTTP 异常）。</p>
     */
    public static boolean isModelQuotaExceeded(Throwable e) {
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 16) {
            if (cur instanceof RestClientResponseException rce
                    && isQuotaLike(rce.getStatusCode().value(), rce.getResponseBodyAsString())) {
                return true;
            }
            if (cur instanceof WebClientResponseException wce
                    && isQuotaLike(wce.getStatusCode().value(), wce.getResponseBodyAsString())) {
                return true;
            }
            if (aiExClassifies(cur, true)) {
                return true;
            }
            cur = cur.getCause();
            depth++;
        }
        return false;
    }

    /**
     * M8-9m：统一额度失败判定——原始 403+quota（cause 链）或已是 40303 业务异常
     * （如 {@code QuotaShortCircuitInterceptor} 短路抛出、无原始 cause）。
     */
    public static boolean isQuotaFailure(Throwable e) {
        return isModelQuotaExceeded(e)
                || (e instanceof BusinessException be
                    && be.getCode() == ErrorCode.MODEL_QUOTA_EXCEEDED.code());
    }

    private static boolean isQuotaLike(int status, String body) {
        // S-A3/A-5（P1-②）：额度类 = 429（DashScope Throttling.* 限流/额度窗口）或错误体含 quota/throttling；
        // 其余 403（无任何额度码）不再是"无分类"，走 isModelUnavailable → 40302。
        if (status == 429) {
            return true;
        }
        return body != null && (body.toLowerCase().contains("quota")
                || body.toLowerCase().contains("throttling"));
    }

    /**
     * S-A3/A-5（P1-②）：沿 cause 链识别"模型不可用/无权限"——403 且错误体无任何额度码
     * （如模型未授权/AccessDenied）。命中时 {@link #rethrowIfQuotaExceeded} 上抛
     * {@link ErrorCode#MODEL_UNAVAILABLE}（40302），不再吞成兜底文案；
     * {@link #isModelQuotaExceeded} 保持额度-only（绊线短路语义不扩面）。
     */
    public static boolean isModelUnavailable(Throwable e) {
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 16) {
            if (cur instanceof RestClientResponseException rce
                    && rce.getStatusCode().value() == 403
                    && !isQuotaLike(403, rce.getResponseBodyAsString())) {
                return true;
            }
            if (cur instanceof WebClientResponseException wce
                    && wce.getStatusCode().value() == 403
                    && !isQuotaLike(403, wce.getResponseBodyAsString())) {
                return true;
            }
            if (aiExClassifies(cur, false)) {
                return true;
            }
            cur = cur.getCause();
            depth++;
        }
        return false;
    }

    /**
     * 命中额度不足时上抛业务异常 40303；已是 40303 业务异常则原样上抛，
     * 避免多层包装。未命中则静默返回（由调用方继续原兜底逻辑）。
     */
    public static void rethrowIfQuotaExceeded(Throwable e) {
        if (e instanceof BusinessException be
                && (be.getCode() == ErrorCode.MODEL_QUOTA_EXCEEDED.code()
                    || be.getCode() == ErrorCode.MODEL_UNAVAILABLE.code())) {
            throw be;
        }
        if (isModelQuotaExceeded(e)) {
            throw new BusinessException(ErrorCode.MODEL_QUOTA_EXCEEDED.code(),
                    ErrorCode.MODEL_QUOTA_EXCEEDED.message(), e);
        }
        // S-A3/A-5（P1-②）：403 无额度码 → 40302 模型不可用/无权限（用户可见"请切换模型"）
        if (isModelUnavailable(e)) {
            throw new BusinessException(ErrorCode.MODEL_UNAVAILABLE.code(),
                    ErrorCode.MODEL_UNAVAILABLE.message(), e);
        }
    }
}
