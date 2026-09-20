package com.travel.memory.shortterm;

/**
 * RK-10/D-0：会话摘要存储端口（V3 前置，G11-①）。
 *
 * <p>签名=SummaryAssembler 预检记录的 Redis 操作组（E-21 提取语义），仅抽象存储语义、
 * 零 Redis 类型泄漏；实现方负责键构建与序列化细节。当前实现=
 * chat-domain RedisSummaryStoreAdapter（D-1 起接入消费方）。</p>
 */
public interface SummaryStorePort {

    /** 读取摘要正文（无= null） */
    String loadSummaryText(String sessionId);

    /** 读取摘要 meta JSON（无= null） */
    String loadSummaryMeta(String sessionId);

    /**
     * Lua CAS 写入（expectedVersion 不匹配即放弃，版本较大者胜）。
     *
     * @return 是否写入成功（脚本返回 1=成功；null/其他=版本冲突或异常）
     */
    boolean saveSummaryCas(String sessionId, int expectedVersion, String text,
                           String metaJson, long ttlSeconds);

    /** 旧双 set 路径（casEnabled=false 回退开关路径） */
    void saveSummaryPair(String sessionId, String text, String metaJson, long ttlDays);

    /** 双键 TTL 刷新（正文+meta） */
    void refreshTtl(String sessionId, long ttlDays);
}
