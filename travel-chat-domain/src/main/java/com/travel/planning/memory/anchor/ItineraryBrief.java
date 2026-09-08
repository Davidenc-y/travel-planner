package com.travel.planning.memory.anchor;

import java.util.List;

/**
 * M23（E1）：锚定行程的注入视图（brief）——供【锚定行程】上下文段渲染。
 *
 * <p>由 planning 侧 {@code ItineraryBriefPortImpl} 从 t_itinerary 装配；
 * 渲染硬预算：attractions 名单截断 {@code travel.chat.anchor.brief-max-attractions=12}。
 * M28-3：新增 startDate（出发日期，取 t_itinerary.start_date——聊天创建/改签时
 * 从 routePlan 首日日期提取），供 preferenceSync 同步偏好标签"出发日期"。
 * M28-12：新增 interests（兴趣，取 t_itinerary.interests JSON 数组文本），供
 * preferenceSync 同步偏好标签"兴趣"。</p>
 */
public record ItineraryBrief(
        Long id,
        String title,
        String destination,
        Integer days,
        String startDate,
        String budget,
        String party,
        List<String> interests,
        Integer version,
        List<String> attractionNames) {
}
