package com.travel.memory.knowledge.dto;

/**
 * 共识条目：同主题内 createdAt 晚者胜；feedback 覆盖旧 constraint（F85）。
 *
 * <p>E-2b 自 chat-domain SessionFactConsolidator 嵌套类型提升至 memory 契约模块
 * （C 案 DTO 提升，用户确认；E-30 授权 Facade 签名同步）。原语义逐字迁移。</p>
 */
public record ConsensusEntry(Topic topic, String value, String type, String createdAt) {
}
