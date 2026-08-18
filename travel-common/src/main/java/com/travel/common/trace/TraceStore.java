package com.travel.common.trace;

import com.travel.common.entity.AgentTrace;

/**
 * Agent 追溯存储端口（F89）。
 *
 * <p>抽象落库实现：MySQL（默认）/ 日志降级。planning 与 knowledge 各提供实现，
 * 便于未来拆 travel-trace 模块时只迁移本接口与实现。</p>
 */
public interface TraceStore {

    /** 保存一条追溯记录（实现方保证不抛异常，失败降级日志） */
    void save(AgentTrace trace);
}
