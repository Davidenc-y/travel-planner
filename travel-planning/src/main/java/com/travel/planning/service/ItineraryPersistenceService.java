package com.travel.planning.service;

import com.travel.common.entity.Itinerary;
import com.travel.planning.repository.ItineraryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
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

    /** M11-1：版本快照服务（可选注入；缺失时仅不记录版本） */
    private ItineraryVersionService versionService;

    @Autowired(required = false)
    void setItineraryVersionService(ItineraryVersionService versionService) {
        this.versionService = versionService;
    }

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

    /** M4-8：全链路成功——终态更新 GENERATED + 产物回填（约束快照回退读列） */
    @Transactional
    public int updateCompleted(Long id, String status, String content, String mindmapData,
                               java.math.BigDecimal estimatedCost) {
        return updateCompleted(id, status, content, mindmapData, estimatedCost, null, null, null);
    }

    /**
     * M28-7：带显式约束快照的终态更新——聊天 REFINE 必须走此重载：
     * 约束列更新（applyRefinedConstraints）在本方法之后执行，回退读列会把
     * vN 快照错记为 v(N-1) 的旧约束。
     */
    @Transactional
    public int updateCompleted(Long id, String status, String content, String mindmapData,
                               java.math.BigDecimal estimatedCost,
                               Integer days, java.math.BigDecimal budget, String startDate) {
        Itinerary patch = new Itinerary();
        patch.setId(id);
        patch.setStatus(status);
        patch.setContent(content);
        patch.setMindmapData(mindmapData);
        patch.setEstimatedCost(estimatedCost);
        int rows = itineraryMapper.updateById(patch);
        // M11-1：终态内容变化后记录历史版本与 diff（失败不影响主流程）
        if (rows > 0 && versionService != null) {
            versionService.recordFinalized(id, content, mindmapData, estimatedCost,
                    days, budget, startDate);
        }
        return rows;
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
