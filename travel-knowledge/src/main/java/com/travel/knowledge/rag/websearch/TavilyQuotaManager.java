package com.travel.knowledge.rag.websearch;

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
public class TavilyQuotaManager {

    private static final String KEY_PREFIX = "travel:tavily:credits:";

    private final StringRedisTemplate redis;
    private final int monthlyCreditLimit;
    private final int monthlyWarnThreshold;
    private final int monthlyDegradeThreshold;

    /**
     * v2.0.7.28（2026-09-29）：阈值由字段 @Value 改构造器注入——Spring 装配语义不变
     * （三键默认值逐字保留），纯单测可直接构造（原字段注入在无 Spring 上下文时默认 0=
     * 阈值失效不可测；ReflectionTestUtils 为项目反模式禁令）。
     */
    public TavilyQuotaManager(StringRedisTemplate redis,
                              @Value("${travel.rag.web-search.monthly-credit-limit:1000}") int monthlyCreditLimit,
                              @Value("${travel.rag.web-search.monthly-warn-threshold:800}") int monthlyWarnThreshold,
                              @Value("${travel.rag.web-search.monthly-degrade-threshold:500}") int monthlyDegradeThreshold) {
        this.redis = redis;
        this.monthlyCreditLimit = monthlyCreditLimit;
        this.monthlyWarnThreshold = monthlyWarnThreshold;
        this.monthlyDegradeThreshold = monthlyDegradeThreshold;
    }

    private String currentMonthKey() {
        return KEY_PREFIX + YearMonth.now();
    }

    /**
     * 剩余额度是否足够（搜索调用前检查）。
     * v2.0.7.28：Redis 不可达时 fail-open 放行+WARN——配额器是计费护栏非业务闸，
     * Redis 故障不应阻断 web-search 主链（超时/降级链已在适配器层兜底）。
     */
    public boolean canSearch() {
        try {
            String used = redis.opsForValue().get(currentMonthKey());
            int usedCount = used == null ? 0 : Integer.parseInt(used);
            return usedCount < monthlyCreditLimit;
        } catch (Exception e) {
            log.warn("[TavilyQuota] canSearch Redis 不可达，fail-open 放行: {}", e.getMessage());
            return true;
        }
    }

    /**
     * 2026-09-30 审计修复：外部证据表明额度已耗尽（如 API 432）——把计数补推到硬限。
     * 场景：SNI 选择性阻断期间失败调用未计数，Redis 读数低于实际消耗；432 是 API 侧
     * 权威口径，补推后 canSearch()/currentMode() 立即 exhausted，后续零外发零浪费。
     */
    public void markExternallyExhausted() {
        // v2.0.7.28 修正（2026-09-29）：v2.0.7.27 首版用 increment(limit)=叠加超推
        // （832+1000=1832 且重复 432 持续膨胀）——改为"不低于硬限"幂等语义：
        // 仅当当前读数低于硬限时才 SET 至硬限，重复调用零副作用。
        try {
            String key = currentMonthKey();
            String used = redis.opsForValue().get(key);
            int current = used == null ? 0 : Integer.parseInt(used);
            if (current < monthlyCreditLimit) {
                redis.opsForValue().set(key, String.valueOf(monthlyCreditLimit), Duration.between(
                        LocalDateTime.now(), YearMonth.now().atEndOfMonth().atTime(23, 59, 59)));
            }
            log.warn("[TavilyQuota] 外部证据(432)标记 exhausted: 计数 {} -> 硬限 {}（幂等 SET）",
                    current, monthlyCreditLimit);
        } catch (Exception e) {
            // Redis 不可达：记账失败不阻断降级链（canSearch 的 fail-open 已保证后续口径）
            log.warn("[TavilyQuota] markExternallyExhausted 记账失败（Redis 不可达，降级链继续）: {}", e.getMessage());
        }
    }

    /** 消耗 1 credit（搜索成功后调用；v2.0.7.28：Redis 不可达吞异常 WARN——计费丢失不阻断业务） */
    public void consume() {
        try {
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
        } catch (Exception e) {
            log.warn("[TavilyQuota] consume 计数失败（Redis 不可达，本次消耗未记账）: {}", e.getMessage());
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
        try {
            String used = redis.opsForValue().get(currentMonthKey());
            return used == null ? 0 : Integer.parseInt(used);
        } catch (Exception e) {
            log.warn("[TavilyQuota] getUsedCount Redis 不可达按 0 读（观测面）: {}", e.getMessage());
            return 0;
        }
    }
}
