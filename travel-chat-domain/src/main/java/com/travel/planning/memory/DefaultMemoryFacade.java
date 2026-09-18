package com.travel.planning.memory;

import com.travel.common.entity.TravelProfile;
import com.travel.common.entity.UserBehaviorProfile;
import com.travel.memory.anchor.SessionAnchorStore;
import com.travel.memory.knowledge.dto.ConsensusEntry;
import com.travel.planning.memory.knowledge.SessionFactConsolidator;
import com.travel.memory.longterm.behavior.BehaviorProfileService;
import com.travel.memory.shortterm.SessionMemoryPort;
import com.travel.memory.MemoryFacade;
import com.travel.planning.service.TravelProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * B3.3：{@link MemoryFacade} 默认委托实现——零行为变更的接口深化（不改任何调用方，
 * 现阶段零注入消费方；调用点切换经人工确认后留待后续批次）。
 *
 * <p>委托既有实现类：anchor 级 {@link SessionAnchorStore#getAnchors}、
 * longterm 级 {@link TravelProfileService#getOrCreate}（R7-memory-mapping
 * 标注的只读主入口）；MI-3 增补：shortterm 级读入口 {@link SessionMemoryPort#getSummaryOrEmpty}、
 * knowledge 级 facts 触发入口 {@link SessionFactConsolidator#consolidate}（门面覆盖面 2→4）。</p>
 *
 * <p>R7 阶段二收编（MM-1a.1）：按 R7-memory-mapping §汇总表建议清单扩至全量门面方法，
 * 全部纯委托既有实现类公共入口（零逻辑、零命名/日志变更）；新增注入
 * {@link BehaviorProfileService}（无接口类直注，映射表附注 3：无接口适配成本）。</p>
 */
@Component
@RequiredArgsConstructor
public class DefaultMemoryFacade implements MemoryFacade {

    private final SessionAnchorStore sessionAnchorStore;
    private final TravelProfileService travelProfileService;
    private final SessionMemoryPort sessionMemoryPort;
    private final SessionFactConsolidator sessionFactConsolidator;
    private final BehaviorProfileService behaviorProfileService;

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
    public List<ConsensusEntry> consolidateFacts(List<Map<String, Object>> hits) {
        return sessionFactConsolidator.consolidate(hits);
    }

    @Override
    public void beginRequest() {
        sessionMemoryPort.beginRequest();
    }

    @Override
    public void endRequest() {
        sessionMemoryPort.endRequest();
    }

    @Override
    public String composeHistory(String sessionId, int maxTurns) {
        return sessionMemoryPort.composeHistoryContext(sessionId, maxTurns);
    }

    @Override
    public void summarizeAsync(String sessionId) {
        sessionMemoryPort.summarizeAsync(sessionId);
    }

    @Override
    public boolean finalizeSummary(String sessionId) {
        return sessionMemoryPort.finalizeSummary(sessionId);
    }

    @Override
    public String composeRecentWindow(String sessionId, int turns) {
        return sessionMemoryPort.composeRecentWindow(sessionId, turns);
    }

    @Override
    public int countUserTurns(String sessionId) {
        return sessionMemoryPort.countUserTurns(sessionId);
    }

    @Override
    public int totalHistoryTokens(String sessionId) {
        return sessionMemoryPort.totalHistoryTokens(sessionId);
    }

    @Override
    public int estimateTokens(String text) {
        return sessionMemoryPort.estimateTokens(text);
    }

    @Override
    public String truncateByTokens(String text, int maxTokens) {
        return sessionMemoryPort.truncateByTokens(text, maxTokens);
    }

    @Override
    public TravelProfile updateProfile(Long userId, String preferredDestinations, String preferredInterests,
                                       String budgetRange, String travelStyle, String consumeLevel) {
        return travelProfileService.update(userId, preferredDestinations, preferredInterests,
                budgetRange, travelStyle, consumeLevel);
    }

    @Override
    public void recordTrip(Long userId, String destination, String interests, String title,
                           BigDecimal budget, String party) {
        travelProfileService.recordTrip(userId, destination, interests, title, budget, party);
    }

    @Override
    public Optional<UserBehaviorProfile> getBehavior(Long userId) {
        return behaviorProfileService.getBehavior(userId);
    }

    @Override
    public void recomputeBehaviorIfEnabled(Long userId) {
        behaviorProfileService.recomputeIfEnabled(userId);
    }

    @Override
    public List<Long> replaceAnchors(Long userId, String sessionId, List<Long> requested) {
        return sessionAnchorStore.replaceAnchors(userId, sessionId, requested);
    }

    @Override
    public String renderAnchorSection(Long userId, List<Long> anchorIds) {
        return sessionAnchorStore.renderSection(userId, anchorIds);
    }

    @Override
    public String renderFacts(List<ConsensusEntry> entries) {
        return sessionFactConsolidator.render(entries);
    }
}
