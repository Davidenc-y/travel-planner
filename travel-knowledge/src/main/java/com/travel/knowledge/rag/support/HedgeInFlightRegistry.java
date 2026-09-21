package com.travel.knowledge.rag.support;

import com.travel.knowledge.rag.model.SearchResult;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * S-B1/B-2（L1c）：路由层对冲单飞注册表。
 *
 * <p>RagDispatcher 进入路由层时按缓存键预启 heuristic→hybrid 对冲 future 并 {@link #register};
 * 路由层工具随后执行 hybrid 时经 {@link #find} 复用同一 future（双倍成本→单倍）；
 * 超预算→收割对冲结果。仅返回值写 QueryTtlCache（查询缓存契约不变，B-2 接线）。</p>
 *
 * <p>并发纪律（E-48/skill F27/F23/F46）：</p>
 * <ul>
 *   <li>requestId/缓存键键控 {@link ConcurrentHashMap}——零 ThreadLocal、零共享可变状态；</li>
 *   <li>executor 无关：future 由调用方构建（沿用既有 EXECUTOR，不新建公共池），本表只做
 *       putIfAbsent 单飞与完成自移除；</li>
 *   <li>完成自移除：{@code whenComplete} 挂钩，正常/异常/取消三种终态均即时出表，杜绝长期泄漏
 *       （WebEnrichWritebackService inFlight 先例 + QuotaTripwire TTL 同型纪律）；前提=调用方
 *       future 必须有界（B-2 预启 future 携带 orTimeout，F23）。</li>
 * </ul>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Component
public class HedgeInFlightRegistry {

    /** cacheKey → 在飞对冲 future。 */
    private final ConcurrentMap<String, CompletableFuture<List<SearchResult>>> inFlight = new ConcurrentHashMap<>();

    /**
     * 单飞注册：键空闲则登记 future（并挂钩完成自移除）返回之；键在飞则忽略入参、
     * 返回既有 future（调用方应复用其结果，不重复执行检索）。
     */
    public CompletableFuture<List<SearchResult>> register(String cacheKey,
                                                          CompletableFuture<List<SearchResult>> future) {
        CompletableFuture<List<SearchResult>> existing = inFlight.putIfAbsent(cacheKey, future);
        if (existing != null) {
            return existing;
        }
        future.whenComplete((result, error) -> inFlight.remove(cacheKey));
        return future;
    }

    /** 复用查询：返回该键的在飞 future（无则 empty）。 */
    public Optional<CompletableFuture<List<SearchResult>>> find(String cacheKey) {
        return Optional.ofNullable(inFlight.get(cacheKey));
    }

    /**
     * F23 收口：超预算/异常路径对在飞 future cancel(true) 并立即出表。
     * 对已完成 future 为安全 no-op（cancel 返回 false）。
     */
    public boolean cancelAndRemove(String cacheKey) {
        CompletableFuture<List<SearchResult>> future = inFlight.remove(cacheKey);
        return future != null && future.cancel(true);
    }

    /** 当前在飞键数（观测/对账用）。 */
    public int size() {
        return inFlight.size();
    }
}
