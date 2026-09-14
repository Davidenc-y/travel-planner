package com.travel.common.trace;

import com.travel.common.entity.AgentTrace;

/**
 * Agent 追溯存储端口（F89）。
 *
 * <p>抽象落库实现：MySQL（默认）/ 日志降级。当前仅 travel-chat-domain 提供两实现
 * （{@code MysqlTraceStore}/{@code LogTraceStore}，经 {@code travel.trace.store} 条件装配；
 * knowledge 的 {@code travel.trace.enabled} 为独立 RAG 链路开关，与本端口无涉），
 * travel-trace 模块拆分为远期预留（届时只迁移本接口与实现）。</p>
 */
public interface TraceStore {

    /** 保存一条追溯记录（实现方保证不抛异常，失败降级日志） */
    void save(AgentTrace trace);
}
