package com.travel.planning.memory;

import com.travel.common.entity.TravelProfile;
import com.travel.planning.memory.anchor.SessionAnchorStore;
import com.travel.planning.service.TravelProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * B3.3：{@link MemoryFacade} 默认委托实现——零行为变更的接口深化（不改任何调用方，
 * 现阶段零注入消费方；调用点切换经人工确认后留待后续批次）。
 *
 * <p>委托既有实现类：anchor 级 {@link SessionAnchorStore#getAnchors}、
 * longterm 级 {@link TravelProfileService#getOrCreate}（均为 R7-memory-mapping
 * 标注的只读主入口）。</p>
 */
@Component
@RequiredArgsConstructor
public class DefaultMemoryFacade implements MemoryFacade {

    private final SessionAnchorStore sessionAnchorStore;
    private final TravelProfileService travelProfileService;

    @Override
    public List<Long> getAnchors(String sessionId) {
        return sessionAnchorStore.getAnchors(sessionId);
    }

    @Override
    public TravelProfile getOrCreateProfile(Long userId) {
        return travelProfileService.getOrCreate(userId);
    }
}
