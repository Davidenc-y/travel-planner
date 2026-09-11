package com.travel.planning.memory;

import com.travel.common.entity.TravelProfile;

import java.util.List;

/**
 * 记忆体系统一门面（R7.2 阶段一仅定义 + 映射表，阶段二经人工确认后迁移行为）。
 *
 * <p>四级记忆对应实现类（阶段二门面方法逐个镜像其既有公共入口）：
 * <ul>
 *   <li>shortterm → memory.shortterm.SessionMemoryServiceImpl（经 SessionMemoryPort）</li>
 *   <li>longterm → service.TravelProfileService（经 ProfilePort）/ memory.longterm.behavior.BehaviorProfileService</li>
 *   <li>anchor → memory.anchor.SessionAnchorStore</li>
 *   <li>knowledge → memory.knowledge.SessionFactConsolidator</li>
 * </ul>
 *
 * <p>B3.3（阶段二首批收编）：按 R7-memory-mapping 取"只读、被外部调用最频"两入口——
 * anchor 读取 {@link #getAnchors(String)}（映射表三调用点：ChatService/SessionAnchorController/
 * ItineraryVersionPortImpl）与 profile 读取 {@link #getOrCreateProfile(Long)}（映射表七调用点，
 * longterm 级建议门面首入口）；其余映射表候选方法留待后续批次。</p>
 */
public interface MemoryFacade {

    /**
     * 会话锚定行程 id 列表读取（只读）。
     *
     * <p>委托 anchor 级实现类 SessionAnchorStore#getAnchors（M28-13 自动锚定判定与
     * planning 锚定面板同源入口）；无锚定返回空列表。</p>
     */
    List<Long> getAnchors(String sessionId);

    /**
     * 用户画像读取（首次惰性建档；映射表 getOrCreate 的门面名 getOrCreateProfile）。
     *
     * <p>委托 longterm 级实现类 service.TravelProfileService#getOrCreate（经 ProfilePort 同源逻辑）。</p>
     */
    TravelProfile getOrCreateProfile(Long userId);
}
