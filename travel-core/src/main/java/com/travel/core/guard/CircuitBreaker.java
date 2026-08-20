package com.travel.core.guard;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 轻量三态熔断器（F91 下沉至 travel-core，F110-B）。
 *
 * <p>CLOSED（正常）→ 窗口内失败率超阈值 → OPEN（熔断 openTimeoutMs）→ HALF_OPEN
 * （放行一个探测请求）→ 成功回 CLOSED / 失败回 OPEN。线程安全、无外部依赖，
 * 供 planning/knowledge/crawl 复用；接口可替换为 resilience4j 实现。</p>
 */
@Slf4j
public class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long windowMs;
    private final long openTimeoutMs;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong openedAt = new AtomicLong(0);

    public CircuitBreaker(int failureThreshold, long windowMs, long openTimeoutMs) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.windowMs = Math.max(1000, windowMs);
        this.openTimeoutMs = Math.max(1000, openTimeoutMs);
    }

    /**
     * 在熔断保护下执行 supplier。
     *
     * @return 成功返回结果；失败抛 {@link CircuitOpenException}（调用方降级）
     */
    public <T> T call(String key, Supplier<T> supplier) {
        if (isOpen()) {
            throw new CircuitOpenException("熔断中: " + key);
        }
        try {
            T result = supplier.get();
            onSuccess();
            return result;
        } catch (Exception e) {
            onFailure();
            throw e;
        }
    }

    public State state() {
        return state.get();
    }

    private boolean isOpen() {
        if (state.get() == State.OPEN) {
            if (System.currentTimeMillis() - openedAt.get() >= openTimeoutMs) {
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    log.warn("[CircuitBreaker] 进入半开探测: 允许单个请求");
                }
            }
            return state.get() == State.OPEN;
        }
        return false;
    }

    private void onSuccess() {
        if (state.get() == State.HALF_OPEN) {
            state.set(State.CLOSED);
            failures.set(0);
            log.info("[CircuitBreaker] 探测成功，恢复 CLOSED");
        } else {
            failures.set(0);
        }
    }

    private void onFailure() {
        long now = System.currentTimeMillis();
        if (now - windowStart.get() > windowMs) {
            windowStart.set(now);
            failures.set(0);
        }
        if (failures.incrementAndGet() >= failureThreshold) {
            if (state.compareAndSet(State.CLOSED, State.OPEN)
                    || state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                openedAt.set(System.currentTimeMillis());
                log.warn("[CircuitBreaker] 触发熔断 OPEN: 连续失败={}", failures.get());
            }
        }
    }

    /** 熔断异常（调用方据此降级） */
    public static class CircuitOpenException extends RuntimeException {
        public CircuitOpenException(String message) {
            super(message);
        }
    }

    /** 按 key 管理多实例的注册表 */
    public static class Registry {
        private final ConcurrentMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
        private final int failureThreshold;
        private final long windowMs;
        private final long openTimeoutMs;

        public Registry(int failureThreshold, long windowMs, long openTimeoutMs) {
            this.failureThreshold = failureThreshold;
            this.windowMs = windowMs;
            this.openTimeoutMs = openTimeoutMs;
        }

        public CircuitBreaker of(String key) {
            return breakers.computeIfAbsent(key,
                    k -> new CircuitBreaker(failureThreshold, windowMs, openTimeoutMs));
        }
    }
}
