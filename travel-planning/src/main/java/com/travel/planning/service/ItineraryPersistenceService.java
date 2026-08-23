package com.travel.planning.service;

import com.travel.common.entity.Itinerary;
import com.travel.planning.repository.ItineraryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M4-7（P1-5 前置修复 3）：行程持久化独立 Service。
 *
 * <p>修复 ItineraryService.persistItinerary 自调用导致 @Transactional 失效的
 * 既有缺陷（Spring 默认代理模式不拦截 this 调用；注释声称"独立事务"与实际不符）。
 * 拆出后由代理正常织入事务；M4-8 两阶段写入（GENERATING 占位→终态更新）
 * 在本类扩展，保证占位与状态推进的事务语义可控。</p>
 */
@Service
@RequiredArgsConstructor
public class ItineraryPersistenceService {

    private final ItineraryMapper itineraryMapper;

    /**
     * 插入行程（独立事务：单条 insert 原子；M4-8 将扩展为占位+更新两阶段）。
     *
     * @throws org.springframework.dao.DuplicateKeyException 并发双发同 clientRequestId
     *         撞 uk_client_request_id——由调用方捕获转幂等重读（M4-7 修复 2）
     */
    @Transactional
    public Itinerary insert(Itinerary entity) {
        itineraryMapper.insert(entity);
        return entity;
    }

    /**
     * M4-8：插入 GENERATING 占位行（generate 入口即落库——幂等检查前移到占位之前，
     * 失败/超时进程内也可见）。content/mindmap 允许 NULL。
     */
    @Transactional
    public Itinerary insertGenerating(Itinerary entity) {
        itineraryMapper.insert(entity);
        return entity;
    }

    /** M4-8：全链路成功——终态更新 GENERATED + 产物回填 */
    @Transactional
    public int updateCompleted(Long id, String status, String content, String mindmapData,
                               java.math.BigDecimal estimatedCost) {
        Itinerary patch = new Itinerary();
        patch.setId(id);
        patch.setStatus(status);
        patch.setContent(content);
        patch.setMindmapData(mindmapData);
        patch.setEstimatedCost(estimatedCost);
        return itineraryMapper.updateById(patch);
    }

    /** M4-8：状态推进（GENERATING→FAILED 等单字段迁移） */
    @Transactional
    public int updateStatus(Long id, String status) {
        Itinerary patch = new Itinerary();
        patch.setId(id);
        patch.setStatus(status);
        return itineraryMapper.updateById(patch);
    }
}
