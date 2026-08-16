package com.travel.planning.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * LLM 调用统一治理（F75/B3-5）。
 *
 * <p>把后台/辅助 LLM 调用（会话摘要、画像历史压缩、确定性偏好抽取等）纳入统一的
 * 并发许可控制：{@code travel.llm.background-max-concurrent} 限制同时进行的后台 LLM
 * 调用数，避免长对话摘要与行程压缩等并发任务叠加在 Supervisor 主链路之上冲击
 * DashScope 配额/并发（F22 教训）。</p>
 *
 * <p>语义：后台任务（runBackground）拿不到许可则**降级跳过**（不阻断业务）；
 * 请求路径调用（callWithPermit，如偏好抽取）拿不到许可则抛异常，由调用方降级。</p>
 */
@Slf4j
@Component
public class LlmGovernor {

    private static final ExecutorService BACKGROUND_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final Semaphore semaphore;
    private final long acquireTimeoutMs;
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();

    public LlmGovernor(@Value("${travel.llm.background-max-concurrent:2}") int maxConcurrent,
                       @Value("${travel.llm.acquire-timeout-ms:5000}") long acquireTimeoutMs) {
        this.semaphore = new Semaphore(Math.max(1, maxConcurrent), true);
        this.acquireTimeoutMs = Math.max(100, acquireTimeoutMs);
    }

    /**
     * 提交后台 LLM 任务；未能在超时内获取并发许可则降级跳过（仅告警）。
     * 许可在任务执行期间持有（含内部多次 LLM 调用，如摘要+保真校验）。
     */
    public void runBackground(String taskName, Runnable task) {
        BACKGROUND_EXECUTOR.submit(() -> {
            if (!tryAcquire(taskName)) {
                return;
            }
            try {
                task.run();
            } catch (Exception e) {
                log.warn("[LlmGovernor] 后台任务执行失败: task={}, error={}", taskName, e.getMessage());
            } finally {
                release();
            }
        });
    }

    /**
     * 请求路径 LLM 调用（如偏好抽取）：获取许可后执行；超时抛异常由调用方降级。
     */
    public <T> T callWithPermit(String taskName, Supplier<T> fn) {
        if (!tryAcquire(taskName)) {
            throw new IllegalStateException("LLM 并发上限已满，任务降级跳过: " + taskName);
        }
        try {
            return fn.get();
        } finally {
            release();
        }
    }

    private boolean tryAcquire(String taskName) {
        try {
            if (semaphore.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS)) {
                accepted.incrementAndGet();
                return true;
            }
            rejected.incrementAndGet();
            log.warn("[LlmGovernor] 并发许可超时，任务降级跳过: task={}, 并发上限={}, 已接受={}, 已拒绝={}",
                    taskName, semaphore.availablePermits() + 1, accepted.get(), rejected.get());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            rejected.incrementAndGet();
            return false;
        }
    }

    private void release() {
        semaphore.release();
    }

    /** 治理统计（accepted/rejected），用于观测后台 LLM 降级情况 */
    public String stats() {
        return "accepted=" + accepted.get() + ", rejected=" + rejected.get();
    }
}
