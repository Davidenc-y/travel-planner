package com.travel.core.stream;

/**
 * 默认空实现（测试/未启用可观测时使用）。
 */
public final class NoopStreamMetrics implements StreamMetrics {

    public static final NoopStreamMetrics INSTANCE = new NoopStreamMetrics();

    private NoopStreamMetrics() {
    }

    @Override
    public void started(String domain) {
    }

    @Override
    public void completed(String domain, long elapsedMs) {
    }

    @Override
    public void failed(String domain, Throwable error, long elapsedMs) {
    }
}
