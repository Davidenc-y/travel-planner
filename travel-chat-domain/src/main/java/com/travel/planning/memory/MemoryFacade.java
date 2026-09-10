package com.travel.planning.memory;

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
 */
public interface MemoryFacade { /* 方法签名以 R7.2 映射表产出为准，逐个镜像既有入口 */ }
