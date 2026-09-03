package com.travel.planning.agent.supervisor;

import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * M8-9m：请求级“模型额度不足”短路绊线（Tripwire）。
 *
 * <p>同一轮次（clientMessageId，回退 requestId）首次触发额度不足（如 DashScope
 * 403 Free quota exhausted）后置位；图流后续并发/重试的模型调用在发起 HTTP 前
 * 检查并短路，避免对额度已尽的模型重复请求（本次实测单次请求 11ms 内打 3 次
 * 403）。语义定位：确定性不可恢复错误的一次性短路，区别于
 * {@link com.travel.core.guard.CircuitBreaker} 的全局熔断——配额是账号级且用户
 * 切换模型后应立即恢复，因此按请求作用域而不是全局 OPEN 态。</p>
 *
 * <p>生命周期：图流执行器 finally 显式 {@link #clear}；即使异常路径遗漏清理，
 * 5 分钟 TTL 也会让条目自然失效，杜绝长期泄漏。</p>
 */
@Component
public final class QuotaTripwire {

    /** 条目最长存活时间：轮次早已结束，残留状态必须失效。 */
    private static final long TTL_MILLIS = 5 * 60 * 1000L;

    /** scopeKey → 首次触发时间戳（epochMillis）。 */
    private final ConcurrentMap<String, Long> trippedAt = new ConcurrentHashMap<>();

    /** 该作用域是否已触发额度不足（未过期）。 */
    public boolean isTripped(String scopeKey) {
        if (scopeKey == null) {
            return false;
        }
        Long at = trippedAt.get(scopeKey);
        return at != null && System.currentTimeMillis() - at < TTL_MILLIS;
    }

    /** 置位（幂等；重复触发刷新时间戳，保持短路窗口）。 */
    public void trip(String scopeKey) {
        if (scopeKey == null) {
            return;
        }
        trippedAt.put(scopeKey, System.currentTimeMillis());
        evictExpiredIfLarge();
    }

    /** 请求结束显式清理（与 token 采集的 endAndGet 对称）。 */
    public void clear(String scopeKey) {
        if (scopeKey != null) {
            trippedAt.remove(scopeKey);
        }
    }

    /** 惰性清理：仅在条目较多时扫描过期项，避免高频请求下 map 无限增长。 */
    private void evictExpiredIfLarge() {
        if (trippedAt.size() <= 1024) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> it = trippedAt.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (now - e.getValue() >= TTL_MILLIS) {
                it.remove();
            }
        }
    }
}
