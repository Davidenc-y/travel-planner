package com.travel.aigateway.route;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.LocalDateTime;
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

    private volatile String dayKey = "";
    private volatile String hourKey = "";
    private final AtomicLong dayUsed = new AtomicLong();
    private final AtomicLong hourUsed = new AtomicLong();

    public LLMBudgetGuard(boolean enabled, long dailyTokens, long hourlyTokens) {
        this.enabled = enabled;
        this.dailyTokens = Math.max(0, dailyTokens);
        this.hourlyTokens = Math.max(0, hourlyTokens);
    }

    /** 调用前预算检查（超限抛 LLMBudgetExceededException；关=直通） */
    public void checkBeforeCall() {
        if (!enabled) {
            return;
        }
        rollIfNeeded();
        long d = dayUsed.get();
        long h = hourUsed.get();
        if (d >= dailyTokens) {
            throw new LLMBudgetExceededException(
                    "LLM daily token budget exceeded: used=" + d + " limit=" + dailyTokens
                            + " (travel.ai.budget.daily-tokens; E-55)");
        }
        if (h >= hourlyTokens) {
            throw new LLMBudgetExceededException(
                    "LLM hourly token budget exceeded: used=" + h + " limit=" + hourlyTokens
                            + " (travel.ai.budget.hourly-tokens; E-55)");
        }
    }

    /** 调用后按 usage 累计（usage 缺失/异常时按保守估值 4,000 计） */
    public void recordAfterCall(Long usageTokens) {
        if (!enabled) {
            return;
        }
        rollIfNeeded();
        long add = usageTokens != null && usageTokens > 0 ? usageTokens : 4_000L;
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
}
