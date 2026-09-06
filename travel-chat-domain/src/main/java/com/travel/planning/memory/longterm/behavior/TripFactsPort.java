package com.travel.planning.memory.longterm.behavior;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * M17-2：行程事实端口（chat-domain 定义、planning 实现——中立端口范式）。
 *
 * <p>行为画像需要行程维度（目的地/预算/同伴/版本数），而行程仓储位于
 * travel-planning；chat-domain 经本端口取数，不引入模块反向依赖（同
 * ItineraryVersionPort/KnowledgePort 范式）。webflux 进程无实现时，
 * 行程维度聚合自动降级为空（仅 trace/会话维度）。</p>
 */
public interface TripFactsPort {

    /**
     * 拉取窗口内用户的行程事实（仅必要列）。
     *
     * @param userId 用户 ID
     * @param since  窗口起点（含）
     * @return 事实列表（createdAt 倒序不必保证；空列表=无行程或端口未装配）
     */
    List<TripFact> recentTrips(Long userId, LocalDateTime since);

    /** 行程事实（聚合输入的最小字段集） */
    record TripFact(
            String destination,
            Integer days,
            BigDecimal budget,
            String party,
            /** 该行程的版本总数（含 v1；REFINE 倾向 = versions-1） */
            int versions,
            LocalDateTime createdAt) {
    }
}
