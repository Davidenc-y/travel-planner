package com.travel.knowledge.rag.websearch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

/**
 * J-2c：Tavily 月度积分管控（三层递进：硬限/预警/降级）。
 *
 * <p>Redis 计数器 {@code travel:tavily:credits:{yyyy-MM}}，TTL 至月末自动重置。
 * 三层阈值（默认 1000/800/500）：</p>
 * <ul>
 *   <li><b>exhausted</b>（≥1000）：完全停止搜索，返回 empty 静默降级</li>
 *   <li><b>degraded_a</b>（≥800）：暂停 description 批量补全，保留 openHours/ticketPrice</li>
 *   <li><b>degraded_c</b>（≥500）：只处理 rating&gt;4.0 高价值景点</li>
 *   <li><b>normal</b>（&lt;500）：正常运行</li>
 * </ul>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TavilyQuotaManager {

    private static final String KEY_PREFIX = "travel:tavily:credits:";

    private final StringRedisTemplate redis;

    @Value("${travel.rag.web-search.monthly-credit-limit:1000}")
    private int monthlyCreditLimit;

    @Value("${travel.rag.web-search.monthly-warn-threshold:800}")
    private int monthlyWarnThreshold;

    @Value("${travel.rag.web-search.monthly-degrade-threshold:500}")
    private int monthlyDegradeThreshold;

    private String currentMonthKey() {
        return KEY_PREFIX + YearMonth.now();
    }

    /** 剩余额度是否足够（搜索调用前检查） */
    public boolean canSearch() {
        String used = redis.opsForValue().get(currentMonthKey());
        int usedCount = used == null ? 0 : Integer.parseInt(used);
        return usedCount < monthlyCreditLimit;
    }

    /** 消耗 1 credit（搜索成功后调用） */
    public void consume() {
        String key = currentMonthKey();
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            // 首次使用，设置 TTL 至月末
            redis.expire(key, Duration.between(
                    LocalDateTime.now(),
                    YearMonth.now().atEndOfMonth().atTime(23, 59, 59)));
        }
        if (count != null) {
            checkThresholds(count.intValue());
        }
    }

    private void checkThresholds(int used) {
        if (used == monthlyDegradeThreshold) {
            log.warn("[TavilyQuota] 月度降级线: 已用 {}/{} credits, 降级策略C生效: 只处理高价值景点",
                    used, monthlyCreditLimit);
        }
        if (used == monthlyWarnThreshold) {
            log.warn("[TavilyQuota] 月度预警线: 已用 {}/{} credits, 降级策略A生效: 暂停description批量补全",
                    used, monthlyCreditLimit);
        }
        if (used >= monthlyCreditLimit) {
            log.warn("[TavilyQuota] 月度配额耗尽: 已用 {}/{} credits, 后续搜索静默降级直至月初重置",
                    used, monthlyCreditLimit);
        }
    }

    /** 当前管控模式 */
    public String currentMode() {
        int used = getUsedCount();
        if (used >= monthlyCreditLimit) return "exhausted";
        if (used >= monthlyWarnThreshold) return "degraded_a";
        if (used >= monthlyDegradeThreshold) return "degraded_c";
        return "normal";
    }

    /** 剩余 credits */
    public int remaining() {
        return Math.max(0, monthlyCreditLimit - getUsedCount());
    }

    private int getUsedCount() {
        String used = redis.opsForValue().get(currentMonthKey());
        return used == null ? 0 : Integer.parseInt(used);
    }
}
