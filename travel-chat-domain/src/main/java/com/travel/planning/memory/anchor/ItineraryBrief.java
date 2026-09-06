package com.travel.planning.memory.anchor;

import java.util.List;

/**
 * M23（E1）：锚定行程的注入视图（brief）——供【锚定行程】上下文段渲染。
 *
 * <p>由 planning 侧 {@code ItineraryBriefPortImpl} 从 t_itinerary 装配；
 * 渲染硬预算：attractions 名单截断 {@code travel.chat.anchor.brief-max-attractions=12}。</p>
 */
public record ItineraryBrief(
        Long id,
        String title,
        String destination,
        Integer days,
        String budget,
        String party,
        Integer version,
        List<String> attractionNames) {
}
