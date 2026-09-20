package com.travel.memory.knowledge;

import com.travel.memory.knowledge.dto.ConsensusEntry;

import java.util.List;
import java.util.Map;

/**
 * RK-11/D-2：会话事实共识端口（V3 前置，G11-②）。
 *
 * <p>签名=chat-domain SessionFactConsolidator 公有方法逐字提取（E-21/E-30 签名不变重构）；
 * 构造器（ChatWordLists 参数）属实现细节，不入端口。当前实现=SessionFactConsolidator，
 * V3 全量迁移时实现类搬入 travel-memory 后仅需换装配、消费方零改动。</p>
 */
public interface FactConsolidatorPort {

    /** 同主题 constraint/feedback 合并为"最新确认口径"（F85），注入前消解冲突 */
    List<ConsensusEntry> consolidate(List<Map<String, Object>> hits);

    /** 共识条目渲染为注入文本 */
    String render(List<ConsensusEntry> entries);
}
