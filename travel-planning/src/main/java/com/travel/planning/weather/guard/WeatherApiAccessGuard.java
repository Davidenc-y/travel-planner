package com.travel.planning.weather.guard;

import com.travel.core.guard.CircuitBreaker;
import com.travel.core.guard.RequestThrottle;
import com.travel.planning.weather.WeatherProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * M15-1：Open-Meteo 统一外部调用守卫（与 MapApiAccessGuard 同构）：
 * Redis 日预算 3000 → ≤1 QPS 匀速 → 三态熔断 → 真实调用。
 * Redis 不可用 fail-closed；失败也计数（与外部预算语义一致）。
 */
@Slf4j
@Component
public class WeatherApiAccessGuard {

    private final StringRedisTemplate redisTemplate;
    private final WeatherProperties props;
    private final RequestThrottle throttle;
    private final CircuitBreaker breaker;

    public WeatherApiAccessGuard(StringRedisTemplate redisTemplate, WeatherProperties props) {
        this.redisTemplate = redisTemplate;
        this.props = props;
        int intervalMs = Math.max(1, 1000 / Math.max(1, props.getMaxQps()));
        this.throttle = new RequestThrottle(intervalMs, 0, 0);
        this.breaker = new CircuitBreaker(
                props.getCircuitFailureThreshold(),
                props.getCircuitWindowMs(),
                props.getCircuitOpenMs());
    }

    public <T> Optional<T> execute(Supplier<Optional<T>> action) {
        if (!tryAcquire()) {
            return Optional.empty();
        }
        throttle.waitIfNeeded();
        if (breaker.isOpen()) {
            log.warn("[WeatherApi] 熔断中，跳过真实调用");
            return Optional.empty();
        }
        boolean probe = breaker.state() == CircuitBreaker.State.HALF_OPEN;
        if (probe && !breaker.tryAcquireProbe()) {
            return Optional.empty();
        }
        try {
            Optional<T> result = action.get();
            breaker.recordSuccess();
            return result;
        } catch (Exception e) {
            breaker.recordFailure();
            log.warn("[WeatherApi] 调用异常: {}", e.getMessage());
            return Optional.empty();
        } finally {
            if (probe) {
                breaker.releaseProbe();
            }
        }
    }

    private boolean tryAcquire() {
        String day = LocalDate.now().toString().replace("-", "");
        String key = "travel:weather:quota:" + day;
        try {
            Long used = redisTemplate.opsForValue().increment(key);
            if (used != null && used == 1L) {
                redisTemplate.expire(key, Duration.ofDays(2));
            }
            if (used == null || used > props.getDayQuota()) {
                if (used != null) {
                    redisTemplate.opsForValue().decrement(key);
                }
                log.warn("[WeatherApi] 日配额已用尽: {}/{}", used, props.getDayQuota());
                return false;
            }
            if (used >= props.getDayQuota() * 0.9) {
                log.warn("[WeatherApi] 已达 90% 日预算: {}/{}", used, props.getDayQuota());
            }
            return true;
        } catch (Exception e) {
            log.warn("[WeatherApi] Redis 计数失败，fail-closed: {}", e.getMessage());
            return false;
        }
    }
}
