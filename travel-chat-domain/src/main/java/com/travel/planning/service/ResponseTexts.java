package com.travel.planning.service;

/**
 * 用户可见的响应兜底文案常量（M16-2 契约常量化）。
 *
 * <p>收敛此前散落/重复的兜底文案（如 ChatRoutingStep 阻塞与流式两处相同的
 * 通用失败文案），避免双份维护漂移。文案变更需同步评估前端展示与既有测试断言。</p>
 */
public final class ResponseTexts {

    private ResponseTexts() {
    }

    /**
     * 路由/规划执行意外失败时的通用兜底回答（非业务码语义，仅兜底展示）。
     * 此前在 ChatRoutingStep 阻塞路径与流式路径各硬编码一份（L170/L265）。
     */
    public static final String GENERIC_ROUTE_FAILURE = "抱歉，处理您的请求时出现错误，请稍后重试。";
}
