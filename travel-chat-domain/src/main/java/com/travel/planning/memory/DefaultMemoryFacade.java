package com.travel.planning.memory;

import com.travel.common.entity.TravelProfile;
import com.travel.planning.memory.anchor.SessionAnchorStore;
import com.travel.planning.memory.knowledge.SessionFactConsolidator;
import com.travel.planning.memory.shortterm.SessionMemoryPort;
import com.travel.planning.service.TravelProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * B3.3：{@link MemoryFacade} 默认委托实现——零行为变更的接口深化（不改任何调用方，
 * 现阶段零注入消费方；调用点切换经人工确认后留待后续批次）。
 *
 * <p>委托既有实现类：anchor 级 {@link SessionAnchorStore#getAnchors}、
 * longterm 级 {@link TravelProfileService#getOrCreate}（R7-memory-mapping
 * 标注的只读主入口）；MI-3 增补：shortterm 级读入口 {@link SessionMemoryPort#getSummaryOrEmpty}、
 * knowledge 级 facts 触发入口 {@link SessionFactConsolidator#consolidate}（门面覆盖面 2→4）。</p>
 */
@Component
@RequiredArgsConstructor
public class DefaultMemoryFacade implements MemoryFacade {

    private final SessionAnchorStore sessionAnchorStore;
    private final TravelProfileService travelProfileService;
    private final SessionMemoryPort sessionMemoryPort;
    private final SessionFactConsolidator sessionFactConsolidator;

    @Override
    public List<Long> getAnchors(String sessionId) {
        return sessionAnchorStore.getAnchors(sessionId);
    }

    @Override
    public TravelProfile getOrCreateProfile(Long userId) {
        return travelProfileService.getOrCreate(userId);
    }

    @Override
    public String getSummary(String sessionId) {
        return sessionMemoryPort.getSummaryOrEmpty(sessionId);
    }

    @Override
    public List<SessionFactConsolidator.ConsensusEntry> consolidateFacts(List<Map<String, Object>> hits) {
        return sessionFactConsolidator.consolidate(hits);
    }
}
