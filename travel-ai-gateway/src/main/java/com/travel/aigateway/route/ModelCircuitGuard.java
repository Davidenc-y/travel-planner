package com.travel.aigateway.route;

import com.travel.aigateway.core.ModelCircuitOpenException;
import com.travel.core.guard.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.function.Supplier;

/**
 * M10-2c：按模型 key 维度熔断（复用 travel-core CircuitBreaker.Registry）。
 *
 * <p>失败判定：连接超时/5xx/429 计失败；40005/40303 等配置/额度类错误不计
 * （语义与 QuotaTripwire 对称）。OPEN 时直接抛
 * {@link ModelCircuitOpenException}（服务层映射 40304）；半开探测成功自动回 CLOSED。</p>
 */
@Slf4j
public final class ModelCircuitGuard {

    private final boolean enabled;
    private final CircuitBreaker.Registry registry;

    public ModelCircuitGuard(boolean enabled, int failureThreshold, long openMs) {
        this.enabled = enabled;
        this.registry = new CircuitBreaker.Registry(failureThreshold, 60_000, openMs);
    }

    /** 同步调用保护；失败按 {@link #shouldCountFailure} 判定后计次。 */
    public <T> T call(String modelKey, Supplier<T> supplier) {
        if (!enabled) {
            return supplier.get();
        }
        String key = "model:" + modelKey;
        CircuitBreaker breaker = registry.of(key);
        if (breaker.isOpen()) {
            throw open(key);
        }
        boolean probe = breaker.state() == CircuitBreaker.State.HALF_OPEN;
        if (probe && !breaker.tryAcquireProbe()) {
            throw open(key);
        }
        try {
            T result = supplier.get();
            breaker.recordSuccess();
            return result;
        } catch (Exception e) {
            if (shouldCountFailure(e)) {
                breaker.recordFailure();
            }
            throw e;
        } finally {
            if (probe) {
                breaker.releaseProbe();
            }
        }
    }

    /**
     * 流式调用保护：OPEN 时在订阅前拒绝；Flux onError 命中可熔断类错误后计次。
     * 订阅前检查与 Flux 异步错误解耦，不阻塞事件流。
     */
    public Flux<?> stream(String modelKey, Supplier<Flux<?>> supplier) {
        if (!enabled) {
            return supplier.get();
        }
        String key = "model:" + modelKey;
        CircuitBreaker breaker = registry.of(key);
        if (breaker.isOpen()) {
            throw open(key);
        }
        boolean probe = breaker.state() == CircuitBreaker.State.HALF_OPEN;
        if (probe && !breaker.tryAcquireProbe()) {
            throw open(key);
        }
        Flux<?> flux = supplier.get();
        if (flux == null) {
            if (probe) {
                breaker.releaseProbe();
            }
            return null;
        }
        return flux
                .doOnComplete(breaker::recordSuccess)
                .doOnError(e -> {
                    if (shouldCountFailure(e)) {
                        breaker.recordFailure();
                    }
                })
                .doFinally(signal -> {
                    if (probe) {
                        breaker.releaseProbe();
                    }
                });
    }

    /** 可熔断失败：连接类/5xx/429；40005/40303 配置与额度类不熔断。 */
    private static boolean shouldCountFailure(Throwable e) {
        if (e == null) {
            return false;
        }
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 12) {
            if (cur instanceof org.springframework.web.reactive.function.client.WebClientResponseException wce) {
                int status = wce.getStatusCode().value();
                return status == 429 || status >= 500;
            }
            if (cur instanceof org.springframework.web.client.RestClientResponseException rce) {
                int status = rce.getStatusCode().value();
                return status == 429 || status >= 500;
            }
            if (cur instanceof java.net.http.HttpTimeoutException
                    || cur instanceof java.net.SocketTimeoutException
                    || cur instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            cur = cur.getCause();
            depth++;
        }
        return false;
    }

    private static ModelCircuitOpenException open(String key) {
        log.warn("[ModelCircuit] 模型熔断 OPEN，拒绝调用: {}", key);
        return new ModelCircuitOpenException("模型熔断中: " + key);
    }
}
