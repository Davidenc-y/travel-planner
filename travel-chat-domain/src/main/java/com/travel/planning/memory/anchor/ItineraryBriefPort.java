package com.travel.planning.memory.anchor;

import java.util.List;
import java.util.Optional;

/**
 * M23（E1）：锚定行程 brief 读取端口（chat-domain 定义、planning 实现——
 * 同 ItineraryVersionPort 中立端口范式，避免领域模块反向依赖行程仓储）。
 */
public interface ItineraryBriefPort {

    /** 本人行程 brief（归属不符/不存在 → empty；调用方据此做锚定自愈剔除）。 */
    Optional<ItineraryBrief> briefOf(Long userId, Long itineraryId);

    /** 会话关联行程 id（t_itinerary.session_id 查询；create-on-chat/REFINE 关联面）。 */
    List<Long> findSessionItineraryIds(String sessionId);
}
