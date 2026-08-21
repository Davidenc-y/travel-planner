package com.travel.planning.memory.shortterm;

/**
 * 短期会话记忆端口（F50/Phase A）。
 *
 * <p>负责从会话历史中组装可注入 Supervisor 的上下文；实现与存储解耦，
 * 便于未来拆分 travel-memory 模块时仅迁移实现。</p>
 */
public interface SessionMemoryPort {

    /** M3-9：请求开始（清空请求内消息快照；默认空实现，避免破坏其他实现） */
    default void beginRequest() {
    }

    /** M3-9：请求结束（清理 ThreadLocal） */
    default void endRequest() {
    }

    /**
     * 组装最近 maxTurns 轮历史上下文（含 token 截断）；无历史返回空串。
     *
     * @param sessionId 会话 ID
     * @param maxTurns  最多保留轮数（每轮 user+assistant 两条）
     * @return 形如 {@code 【历史对话】\nuser: ...\nassistant: ...} 的文本
     */
    String composeHistoryContext(String sessionId, int maxTurns);

    /**
     * 获取 Redis 会话摘要；无摘要返回空串（F55/B1）
     */
    String getSummaryOrEmpty(String sessionId);

    /**
     * 获取摘要信息（文本 + 游标 lastMessageId + 版本），用于滚动压缩（F58/B1.2）
     */
    SummaryInfo getSummaryInfo(String sessionId);

    /**
     * 异步生成并保存会话摘要（Redis，TTL 见配置）；不阻塞调用方（F55/B1）
     */
    void summarizeAsync(String sessionId);

    /**
     * 组装最近 turns 轮原文（无【历史对话】头，供摘要模式下滑动窗口使用）
     */
    String composeRecentWindow(String sessionId, int turns);

    /**
     * 统计会话中 user 消息轮数（摘要触发的轮数维度）
     */
    int countUserTurns(String sessionId);

    /**
     * 汇总全量消息的 token（逐条 estimateTokens 求和，不截断；摘要触发的 token 维度）
     */
    int totalHistoryTokens(String sessionId);

    /**
     * 按 token 预算截断文本（语义句/字符级，追加"…（已截断）"标记；F58/B1.2）
     */
    String truncateByTokens(String text, int maxTokens);

    /** 摘要信息（文本 + 上次覆盖的最后一条消息 id + 版本） */
    record SummaryInfo(String text, Long lastMessageId, int version) {
        public boolean isEmpty() {
            return text == null || text.isBlank();
        }
    }

    /**
     * 估算文本 token（中文≈1字/token，其他≈4字符/token，最小 1）
     */
    int estimateTokens(String text);
}
