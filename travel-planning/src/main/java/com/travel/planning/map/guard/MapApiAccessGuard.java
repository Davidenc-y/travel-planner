package com.travel.planning.map.guard;

import com.travel.core.guard.CircuitBreaker;
import com.travel.core.guard.RequestThrottle;
import com.travel.planning.map.amap.AmapMapProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 高德外部调用统一入口（M12-2）：配额 → 限频 → 熔断 → 真实调用。
 *
 * <p>语义：配额不足/熔断/异常一律返回 Optional.empty()（上层降级）；
 * 配额只在真实网络调用前扣减一次，失败也计数（与爬虫预算语义一致）。</p>
 */
@Slf4j
@Component
public class MapApiAccessGuard {

    private final MapQuotaGuardService quotaService;
    private final RequestThrottle throttle;
    private final CircuitBreaker breaker;
    private final ConcurrentMap<String, AtomicInteger> attempts = new ConcurrentHashMap<>();

    public MapApiAccessGuard(MapQuotaGuardService quotaService, AmapMapProperties props) {
        this.quotaService = quotaService;
        int intervalMs = Math.max(1, 1000 / Math.max(1, props.getMaxQps()));
        this.throttle = new RequestThrottle(intervalMs, 0, 0);
        this.breaker = new CircuitBreaker(
                props.getCircuitFailureThreshold(),
                props.getCircuitWindowMs(),
                props.getCircuitOpenMs());
    }

    public <T> Optional<T> execute(String api, Supplier<Optional<T>> action) {
        if (!quotaService.tryAcquire(api)) {
            return Optional.empty();
        }
        throttle.waitIfNeeded();
        if (breaker.isOpen()) {
            log.warn("[MapApiAccess] {} 熔断中，跳过真实调用", api);
            return Optional.empty();
        }
        boolean probe = breaker.state() == CircuitBreaker.State.HALF_OPEN;
        if (probe && !breaker.tryAcquireProbe()) {
            return Optional.empty();
        }
        attempts.computeIfAbsent(api, k -> new AtomicInteger()).incrementAndGet();
        try {
            Optional<T> result = action.get();
            breaker.recordSuccess();
            return result;
        } catch (Exception e) {
            breaker.recordFailure();
            log.warn("[MapApiAccess] {} 调用异常: {}", api, e.getMessage());
            return Optional.empty();
        } finally {
            if (probe) {
                breaker.releaseProbe();
            }
        }
    }

    /** 自启动以来的真实网络调用次数（api=route/geocode）。 */
    public int attemptsOf(String api) {
        AtomicInteger counter = attempts.get(api);
        return counter == null ? 0 : counter.get();
    }
}
