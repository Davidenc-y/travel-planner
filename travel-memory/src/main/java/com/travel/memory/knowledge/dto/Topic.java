package com.travel.memory.knowledge.dto;

/**
 * 共识主题（F85）。
 *
 * <p>E-2b 自 chat-domain SessionFactConsolidator 嵌套类型提升至 memory 契约模块
 * （C 案 DTO 提升，用户确认；E-30 授权 Facade 签名同步）。原语义逐字迁移；
 * label 由包私有放宽为 public final——留驻 SessionFactConsolidator 跨包渲染所需，
 * 行为零变更。</p>
 */
public enum Topic {
    BUDGET("预算"), DESTINATION("目的地"), DAYS("天数"),
    PARTY("人数"), STYLE("风格"), INTEREST("兴趣");

    public final String label;

    Topic(String label) {
        this.label = label;
    }
}
