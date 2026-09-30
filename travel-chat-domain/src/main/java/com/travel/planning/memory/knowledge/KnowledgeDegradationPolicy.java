package com.travel.planning.memory.knowledge;

import com.travel.common.util.PromptFiles;

/**
 * AK-4a：Knowledge 检索降级策略对象化（蓝图 §四 设计模式深化；行为等价重构骨架）。
 *
 * <p>将 {@code KnowledgeRetrievalServiceImpl} 既有三分支降级的<b>字面量与话术绑定</b>
 * 收敛为单一策略面（AK-4b 切换调用，本类先落骨架）：</p>
 *
 * <ul>
 *   <li>{@link #EMPTY}——检索成功但 0 条（离域查询）：degradedReason=knowledge_empty，
 *       话术=拒答指引 rag_abstain_note（AF 审计 AR-4 幻觉防线）。</li>
 *   <li>{@link #FALLBACK}——候选全部低置信被滤：degradedReason=rag_all_low_confidence，
 *       话术=同 rag_abstain_note（拒答语义与空结果一致）。</li>
 *   <li>{@link #TRANSIENT}——feign/传输异常（瞬时故障）：degradedReason=knowledge_feign_fail，
 *       话术=rag_transient_note，开关关闭（E-33 默认）回退裸 "[]" 现状。</li>
 * </ul>
 *
 * <p>行为等价红线（P0⑲）：degradedReason 字面量与既有实现<b>逐字一致</b>（trace 面
 * t_agent_trace DEGRADED 契约不变）；话术模板键不变；计数器语义（abstain/degraded）
 * 由调用方保持。回归金护栏=RagInjectionPolicyTest/AG-2b 既有例（AK-4b 全量回归）。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
public enum KnowledgeDegradationPolicy {

    /** 检索成功但 0 条（离域查询）——拒答话术 */
    EMPTY("knowledge_empty", "检索为空"),

    /** 候选全部低置信被滤——降级到拒答话术 */
    FALLBACK("rag_all_low_confidence", "候选全部低置信"),

    /** feign/传输异常（瞬时故障）——瞬时话术或裸 "[]" */
    TRANSIENT("knowledge_feign_fail", "检索失败降级");

    private final String degradedReason;
    private final String logLabel;

    KnowledgeDegradationPolicy(String degradedReason, String logLabel) {
        this.degradedReason = degradedReason;
        this.logLabel = logLabel;
    }

    /** 日志契约：t_agent_trace DEGRADED 标注 reason（与既有字面量逐字一致）。 */
    public String degradedReason() {
        return degradedReason;
    }

    /** 日志契约：降级事件日志短标签。 */
    public String logLabel() {
        return logLabel;
    }

    /** 话术绑定：拒答指引（EMPTY/FALLBACK 共用 rag_abstain_note 模板）。 */
    public String abstainNote() {
        return PromptFiles.get("rag_abstain_note");
    }

    /** 话术绑定：瞬时故障指引（failureNoteEnabled=false=裸 "[]" 现状，E-33；AG-2b）。 */
    public String transientNote(boolean failureNoteEnabled) {
        return failureNoteEnabled ? PromptFiles.get("rag_transient_note") : "[]";
    }
}
