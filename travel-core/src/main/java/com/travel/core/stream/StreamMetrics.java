package com.travel.core.stream;

/**
 * M6：流式可观测指标（框架统一埋点，域实现零成本获得）。
 */
public interface StreamMetrics {

    void started(String domain);

    void completed(String domain, long elapsedMs);

    void failed(String domain, Throwable error, long elapsedMs);
}
