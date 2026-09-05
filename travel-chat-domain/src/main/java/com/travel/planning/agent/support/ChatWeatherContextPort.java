package com.travel.planning.agent.support;

/**
 * M15-1：聊天规划路径的天气上下文端口（chat-domain 定义、planning 实现注入）。
 *
 * <p>沿用 ItineraryVersionPort 中立端口范式，避免 chat-domain 反向依赖
 * travel-planning；关闭/不可解析时返回空串，不影响回答组装。</p>
 */
public interface ChatWeatherContextPort {

    /**
     * 从完整 composed 上下文中解析目的地/天数并生成天气参考段。
     *
     * @return 天气段文本；未启用/无法解析/失败 → 空串
     */
    String build(String composed);
}
