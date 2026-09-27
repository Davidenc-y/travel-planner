package com.travel.planning.memory.knowledge;

/**
 * MemoryConsolidationService 公共契约（AD-2b 接口化）。
 *
 * <p>实现见 {@link MemoryConsolidationServiceImpl}（@Service 在 Impl）；
 * 公有方法签名逐字提取（零语义变更）。包私有 static 常量（LEDGER_CAP/DISTILL_SEQ_PREFIX）
 * 与 LedgerEntry 记录留守实现类（外部零引用，不入公共契约）。</p>
 */
public interface MemoryConsolidationService {

    /** 聚合写前登记台账（M17-1 双写路径的 ledger 进料口）。 */
    void recordChunk(String sessionId, String type, String seq, String content, String createdAt);

    /** 定时聚合入口（调度触发，逐会话 consolidate）。 */
    void scheduledConsolidate();

    /** 会话聚合去重（返回处理条数）。 */
    int consolidateSession(String sessionId);

    /** 会话蒸馏（LLM 压缩历史，返回处理条数）。 */
    int distillSession(String sessionId);
}
