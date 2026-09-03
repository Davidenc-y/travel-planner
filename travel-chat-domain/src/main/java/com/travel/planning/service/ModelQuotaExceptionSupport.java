package com.travel.planning.service;

import com.travel.common.exception.BusinessException;
import com.travel.common.exception.ErrorCode;
import com.travel.planning.memory.pipeline.ChatRoutingStep;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * M8-9i：模型额度不足异常识别与上抛（DashScope OpenAI 兼容端点
 * 403 + “Free quota exhausted”等）。
 *
 * <p>被 {@link ChatRoutingStep} 与 {@link ChatService} 共用：
 * 路由步骤负责“吞异常转兜底文案”前先把额度类异常上抛为业务异常 40303，
 * ChatService 负责补充模型名后沿 SSE/JSON 链路透传，避免前端只看到
 * “Agent 流式调用失败”或原始 403 而没有任何明确提示。</p>
 */
public final class ModelQuotaExceptionSupport {

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
                    && isQuota403(rce.getStatusCode().value(), rce.getResponseBodyAsString())) {
                return true;
            }
            if (cur instanceof WebClientResponseException wce
                    && isQuota403(wce.getStatusCode().value(), wce.getResponseBodyAsString())) {
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

    /**
     * 命中额度不足时上抛业务异常 40303；已是 40303 业务异常则原样上抛，
     * 避免多层包装。未命中则静默返回（由调用方继续原兜底逻辑）。
     */
    public static void rethrowIfQuotaExceeded(Throwable e) {
        if (e instanceof BusinessException be
                && be.getCode() == ErrorCode.MODEL_QUOTA_EXCEEDED.code()) {
            throw be;
        }
        if (isModelQuotaExceeded(e)) {
            throw new BusinessException(ErrorCode.MODEL_QUOTA_EXCEEDED.code(),
                    ErrorCode.MODEL_QUOTA_EXCEEDED.message(), e);
        }
    }

    private static boolean isQuota403(int status, String body) {
        return status == 403 && body != null && body.toLowerCase().contains("quota");
    }
}
