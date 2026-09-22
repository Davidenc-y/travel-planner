package com.travel.memory.detour;

/**
 * U-3b 审计实施（B 案）：DETOUR 摘要过滤闸门的中立端口。
 *
 * <p>背景：SummaryAssembler（V3 迁入 travel-memory）需按 DETOUR 隔离策略过滤摘要，
 * 但 DetourIsolationProperties/DetourWordMatcher 被 ChatService 双消费（留驻 chat-domain）。
 * 本端口遵循 ItineraryVersionPort 中立端口范式：接口在 travel-memory、实现在 chat-domain，
 * 消除跨模块配置类反向依赖。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
public interface SessionDetourFilterPort {

    /**
     * 判断给定摘要文本是否含 DETOUR（离题）内容，需从摘要中过滤。
     *
     * @param summaryText 摘要文本（null/空=false 不过滤）
     * @return true=含 DETOUR 内容应过滤
     */
    boolean shouldFilter(String summaryText);
}
