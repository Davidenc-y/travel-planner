package com.travel.planning.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.travel.common.entity.Itinerary;
import com.travel.common.entity.ItineraryVersion;
import com.travel.planning.memory.longterm.behavior.TripFactsPort;
import com.travel.planning.repository.ItineraryMapper;
import com.travel.planning.repository.ItineraryVersionMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * M17-2：行程事实端口实现（planning 侧，供 chat-domain 行为画像聚合取数）。
 *
 * <p>中立端口范式（同 ItineraryVersionPort/KnowledgePort）：chat-domain 定义端口、
 * planning 实现注入；仅查询必要列，窗口内行程数有界。</p>
 */
@Component
public class TripFactsPortImpl implements TripFactsPort {

    /** 单用户窗口内行程读取上限（行为聚合足够，防异常大用户） */
    private static final int TRIP_LIMIT = 500;

    private final ItineraryMapper itineraryMapper;
    private final ItineraryVersionMapper versionMapper;

    public TripFactsPortImpl(ItineraryMapper itineraryMapper, ItineraryVersionMapper versionMapper) {
        this.itineraryMapper = itineraryMapper;
        this.versionMapper = versionMapper;
    }

    @Override
    public List<TripFact> recentTrips(Long userId, LocalDateTime since) {
        List<Itinerary> rows = itineraryMapper.selectList(new QueryWrapper<Itinerary>()
                .eq("user_id", userId)
                .ge("created_at", since)
                .select("id", "destination", "days", "budget", "party", "created_at")
                .last("LIMIT " + TRIP_LIMIT));
        if (rows.isEmpty()) {
            return List.of();
        }

        // 版本数批量计数（一次 in 查询，避免 N+1）
        List<Long> ids = rows.stream().map(Itinerary::getId).toList();
        Map<Long, Integer> versionCount = new HashMap<>();
        for (ItineraryVersion v : versionMapper.selectList(new QueryWrapper<ItineraryVersion>()
                .in("itinerary_id", ids)
                .select("itinerary_id"))) {
            versionCount.merge(v.getItineraryId(), 1, Integer::sum);
        }

        return rows.stream()
                .map(r -> new TripFact(
                        r.getDestination(),
                        r.getDays(),
                        r.getBudget(),
                        r.getParty(),
                        versionCount.getOrDefault(r.getId(), 1),
                        r.getCreatedAt()))
                .toList();
    }
}
