package com.travel.aigateway.route;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * E-55（2026-09-29 立规）：LLM token 预算硬闸——跨会话/跨调用方的**代码级**强制层。
 *
 * <p>背景：AG 审计实弹窗一小时 382 万 tokens（时长模式梯度压测无轮数上限）致 DashScope
 * 账户欠费；文档型纪律（E-55 规程）换会话/换模型即失效——本类为机制化兜底：无论调用方
 * 是 k6/curl/浏览器/未来脚本，只要走 {@link RoleRoutingChatModel}（chatModel/lightModel
 * 唯一喉道），超预算即拒绝。</p>
 *
 * <p><b>机制</b>：进程内滑动计数（小时+日双层，同 TavilyQuotaManager 语义分层）；调用前查
 * 预算（超限抛 {@link LLMBudgetExceededException}，语义=NonTransient 类由既有降级链消化：
 * chat 主链走 error 帧/直答流 403 包装路径），调用后按 usage 累计。进程内计数=单实例口径
 * （多实例部署时每实例各限，保守方向安全）。</p>
 *
 * <p><b>配置</b>（travel.ai.budget.*）：enabled 默认 <b>true</b>（用户 2026-09-29 授权的
 * 保护性行为变更——事故后立规）；daily-tokens 默认 3,000,000（≈生产日聊天百轮量级）；
 * hourly-tokens 默认 600,000（防小时级失控烧穿——382 万/时事故将被截断在 60 万）。
 * 关闭或调高仅经 Nacos 显式配置，禁止代码改默认值。</p>
 *
 * <p>覆盖面声明：chat 调用（主模型+lightModel 经 RoleRouting 喉道）全覆盖；DashScope
 * rerank/embedding 直连不在本闸（量级小一个数量级，QU/hyde 冷路径另有 llmoff 四键形态）。</p>
 *
 * <p>AK-3b（观测面）：进程内 check/reject 计数（AtomicLong 零依赖形态——
 * travel-ai-gateway pom 无 actuator/micrometer 依赖，MeterRegistry 不可编译=withEf
 * 同族 API 存在性陷阱，二审实证禁引用）；getter 供测试/观测，拒绝时 INFO 摘要行
 * 供审计窗日志取证。跨实例共享口径的权威观测仍是 Redis 键 ai:budget:* 本身
 * （AR-3），本地计数仅进程内辅助。</p>
 */
@Slf4j
public final class LLMBudgetGuard {

    /** 预算超限异常（语义对齐 NonTransient：重试无意义，调用方走降级） */
    public static final class LLMBudgetExceededException extends RuntimeException {
        public LLMBudgetExceededException(String message) {
            super(message);
        }
    }

    private final boolean enabled;
    private final long dailyTokens;
    private final long hourlyTokens;
    /** AI-3a：Redis 共享口径开关（默认 false=E-33 进程内现状零变化；true=多实例共享计数，AK 批开启） */
    private final boolean redisEnabled;
    private final ObjectProvider<StringRedisTemplate> redisProvider;

    /** AR-3：Redis 键（日/时滚动窗，TTL 自清理） */
    private static final String DAY_KEY_PREFIX = "ai:budget:d:";
    private static final String HOUR_KEY_PREFIX = "ai:budget:h:";
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter HOUR_FMT = DateTimeFormatter.ofPattern("yyyyMMddHH");
    private static final Duration DAY_KEY_TTL = Duration.ofHours(26);
    private static final Duration HOUR_KEY_TTL = Duration.ofHours(2);

    private volatile String dayKey = "";
    private volatile String hourKey = "";
    private final AtomicLong dayUsed = new AtomicLong();
    private final AtomicLong hourUsed = new AtomicLong();
    /** AK-3b：进程内观测计数（检查次数/拒绝次数；仅 enabled 态统计） */
    private final AtomicLong checkCount = new AtomicLong();
    private final AtomicLong rejectCount = new AtomicLong();

    /** E-55 既有三参构造（既有单测/装配零破坏）：委托五参=Redis 层关闭。 */
    public LLMBudgetGuard(boolean enabled, long dailyTokens, long hourlyTokens) {
        this(enabled, dailyTokens, hourlyTokens, false, null);
    }

    /** AI-3a：完整构造（ObjectProvider 可选注入——无 Redis bean 时 getIfAvailable()=null，GrayReleaseManager 同款先例）。 */
    public LLMBudgetGuard(boolean enabled, long dailyTokens, long hourlyTokens,
                          boolean redisEnabled, ObjectProvider<StringRedisTemplate> redisProvider) {
        this.enabled = enabled;
        this.dailyTokens = Math.max(0, dailyTokens);
        this.hourlyTokens = Math.max(0, hourlyTokens);
        this.redisEnabled = redisEnabled;
        this.redisProvider = redisProvider;
    }

    /** 调用前预算检查（超限抛 LLMBudgetExceededException；关=直通；Redis 开=共享口径读数，异常降级进程内） */
    public void checkBeforeCall() {
        if (!enabled) {
            return;
        }
        checkCount.incrementAndGet();
        rollIfNeeded();
        if (redisEnabled) {
            long[] used = redisGetUsed();
            if (used != null) {
                if (used[0] >= dailyTokens) {
                    throw reject("LLM daily token budget exceeded (redis): used=" + used[0] + " limit=" + dailyTokens
                            + " (travel.ai.budget.daily-tokens; E-55/AR-3)");
                }
                if (used[1] >= hourlyTokens) {
                    throw reject("LLM hourly token budget exceeded (redis): used=" + used[1] + " limit=" + hourlyTokens
                            + " (travel.ai.budget.hourly-tokens; E-55/AR-3)");
                }
                return;
            }
            // Redis 不可用/异常=降级进程内（现状逻辑为 fallback 层），继续走进程内口径
        }
        long d = dayUsed.get();
        long h = hourUsed.get();
        if (d >= dailyTokens) {
            throw reject("LLM daily token budget exceeded: used=" + d + " limit=" + dailyTokens
                    + " (travel.ai.budget.daily-tokens; E-55)");
        }
        if (h >= hourlyTokens) {
            throw reject("LLM hourly token budget exceeded: used=" + h + " limit=" + hourlyTokens
                    + " (travel.ai.budget.hourly-tokens; E-55)");
        }
    }

    /** AK-3b：拒绝统一出口（计数+INFO 摘要行供审计窗日志取证；异常语义零变化）。 */
    private LLMBudgetExceededException reject(String message) {
        long rejects = rejectCount.incrementAndGet();
        log.info("[LLMBudget] 拒绝摘要（进程内观测）: checks={} rejects={}", checkCount.get(), rejects);
        return new LLMBudgetExceededException(message);
    }

    /** 调用后按 usage 累计（usage 缺失/异常时按保守估值 4,000 计；Redis 开=INCRBY 共享记账，异常降级进程内） */
    public void recordAfterCall(Long usageTokens) {
        if (!enabled) {
            return;
        }
        rollIfNeeded();
        long add = usageTokens != null && usageTokens > 0 ? usageTokens : 4_000L;
        if (redisEnabled && redisRecord(add)) {
            return;
        }
        dayUsed.addAndGet(add);
        hourUsed.addAndGet(add);
        if (log.isDebugEnabled() && (dayUsed.get() % 100_000) < add) {
            log.debug("[LLMBudget] day={}/{} hour={}/{}", dayUsed.get(), dailyTokens, hourUsed.get(), hourlyTokens);
        }
    }

    /** 到达新时段时清零（无锁双检：旧值读偏仅造成保守方向误差） */
    private void rollIfNeeded() {
        LocalDate today = LocalDate.now();
        String dk = today.toString();
        if (!dk.equals(dayKey)) {
            synchronized (this) {
                if (!dk.equals(dayKey)) {
                    dayKey = dk;
                    dayUsed.set(0);
                }
            }
        }
        String hk = LocalDateTime.now().toString().substring(0, 13);
        if (!hk.equals(hourKey)) {
            synchronized (this) {
                if (!hk.equals(hourKey)) {
                    hourKey = hk;
                    hourUsed.set(0);
                }
            }
        }
    }

    /** AR-3：Redis 读数（[日 used, 时 used]）；null=Redis 不可用/异常（调用方降级进程内）。 */
    private long[] redisGetUsed() {
        StringRedisTemplate redis = redisTemplate();
        if (redis == null) {
            return null;
        }
        try {
            String dv = redis.opsForValue().get(DAY_KEY_PREFIX + LocalDate.now().format(DAY_FMT));
            String hv = redis.opsForValue().get(HOUR_KEY_PREFIX + LocalDateTime.now().format(HOUR_FMT));
            return new long[]{dv == null ? 0 : Long.parseLong(dv), hv == null ? 0 : Long.parseLong(hv)};
        } catch (Exception e) {
            log.warn("[LLMBudget] Redis 读数异常降级进程内: {}", e.getMessage());
            return null;
        }
    }

    /** AR-3：INCRBY 两键（首次设 TTL：日 26h/时 2h=滚动窗自清理）；false=Redis 不可用/异常（已降级进程内）。 */
    private boolean redisRecord(long add) {
        StringRedisTemplate redis = redisTemplate();
        if (redis == null) {
            return false;
        }
        try {
            String dk = DAY_KEY_PREFIX + LocalDate.now().format(DAY_FMT);
            Long dNew = redis.opsForValue().increment(dk, add);
            if (dNew != null && dNew == add) {
                redis.expire(dk, DAY_KEY_TTL);
            }
            String hk = HOUR_KEY_PREFIX + LocalDateTime.now().format(HOUR_FMT);
            Long hNew = redis.opsForValue().increment(hk, add);
            if (hNew != null && hNew == add) {
                redis.expire(hk, HOUR_KEY_TTL);
            }
            return true;
        } catch (Exception e) {
            log.warn("[LLMBudget] Redis 记账异常降级进程内: {}", e.getMessage());
            return false;
        }
    }

    /** GrayReleaseManager 同款：无 Redis bean 时 getIfAvailable()=null。 */
    private StringRedisTemplate redisTemplate() {
        return redisProvider == null ? null : redisProvider.getIfAvailable();
    }

    /** 只读快照（可观测/测试用） */
    public long dayUsed() {
        rollIfNeeded();
        return dayUsed.get();
    }

    public long hourUsed() {
        rollIfNeeded();
        return hourUsed.get();
    }

    public boolean enabled() {
        return enabled;
    }

    /** AK-3b：进程内检查次数（仅 enabled 态统计；多实例各自计数，共享口径看 Redis 键）。 */
    public long checkCount() {
        return checkCount.get();
    }

    /** AK-3b：进程内拒绝次数。 */
    public long rejectCount() {
        return rejectCount.get();
    }
}
