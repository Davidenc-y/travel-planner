package com.travel.planning.map.guard;

import com.travel.planning.map.amap.AmapMapProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 高德日/月双预算守卫（M12-2）：Redis 持久计数，重启/多实例不归零。
 *
 * <p>api 取值：route / geocode。Redis 不可用时 fail-closed（拒绝真实调用），
 * 保证“无法计数就不消耗外部配额”。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MapQuotaGuardService {

    private final StringRedisTemplate redisTemplate;
    private final AmapMapProperties props;

    /** @return true=放行（配额内）；false=超限或 Redis 不可用 */
    public boolean tryAcquire(String api) {
        long dayLimit = dayLimit(api);
        long monthLimit = monthLimit(api);
        String dayKey = dayKey(api);
        String monthKey = monthKey(api);
        try {
            Long day = redisTemplate.opsForValue().increment(dayKey);
            if (day != null && day == 1L) {
                redisTemplate.expire(dayKey, Duration.ofDays(35));
            }
            Long month = redisTemplate.opsForValue().increment(monthKey);
            if (month != null && month == 1L) {
                redisTemplate.expire(monthKey, Duration.ofDays(35));
            }
            if (day == null || month == null
                    || day > dayLimit || month > monthLimit) {
                if (day != null) {
                    redisTemplate.opsForValue().decrement(dayKey);
                }
                if (month != null) {
                    redisTemplate.opsForValue().decrement(monthKey);
                }
                log.warn("[MapQuota] {} 配额已用尽: 日 {}/{} 月 {}/{}",
                        api, day, dayLimit, month, monthLimit);
                return false;
            }
            warnIfNeeded(api, dayKey, day, dayLimit, monthKey, month, monthLimit);
            return true;
        } catch (Exception e) {
            log.warn("[MapQuota] Redis 计数失败，fail-closed 拒绝外部调用: api={} error={}",
                    api, e.getMessage());
            return false;
        }
    }

    private void warnIfNeeded(String api, String dayKey, long day, long dayLimit,
                              String monthKey, long month, long monthLimit) {
        String warnKey = dayKey + ":warned";
        if (day >= dayLimit * 0.9 || month >= monthLimit * 0.9) {
            Boolean first = redisTemplate.opsForValue()
                    .setIfAbsent(warnKey, "1", Duration.ofDays(1));
            if (Boolean.TRUE.equals(first)) {
                log.warn("[MapQuota] {} 已达 90% 预算: 日 {}/{} 月 {}/{}",
                        api, day, dayLimit, month, monthLimit);
            }
        }
    }

    private long dayLimit(String api) {
        return "route".equals(api)
                ? props.getRouteDayQuota()
                : props.getGeocodeDayQuota();
    }

    private long monthLimit(String api) {
        return "route".equals(api)
                ? props.getRouteMonthQuota()
                : props.getGeocodeMonthQuota();
    }

    private String dayKey(String api) {
        return "travel:map:quota:" + api + ":"
                + LocalDate.now().toString().replace("-", "");
    }

    private String monthKey(String api) {
        return "travel:map:quota:" + api + ":"
                + YearMonth.now().toString().replace("-", "");
    }
}
