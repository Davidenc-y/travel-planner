package com.travel.knowledge.rag.support;

import com.travel.knowledge.rag.model.SearchResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * AV-2：并发查询协衡注册表——同 key 在飞请求共享一次计算。
 *
 * <p>RagDispatcher.dispatch 在 QueryTtlCache miss 后经 {@link #coalesce} 单飞：
 * 首个请求（leader）在调用方线程同步执行计算并 complete，后续同 key 请求（joiner）
 * join 共享结果——计算中请求零额外负载（GV-2/GM-5：5 并发同 query 五次全量检索→一次）。
 * 同型先例=HedgeInFlightRegistry（putIfAbsent 单飞+完成自移除）。</p>
 *
 * <p>并发纪律：</p>
 * <ul>
 *   <li>P0⑲：in-flight key 与 QueryTtlCache key 同源——调用方传入
 *       {@code QueryTtlCache.buildKey} 产物（RagDispatcher cacheKey）原样复用，
 *       禁任何二次拼接；</li>
 *   <li>P0⑳：异常传播=join 失败→catch→无协衡降级直搜（joiner 各自回退
 *       {@code computation.get()}），leader 原样抛出原始异常——永不因协衡而多失败，
 *       禁向上抛 ExecutionException/CompletionException；</li>
 *   <li>synchronous-leader：leader 在调用方线程同步计算，零新增线程池
 *       （F46 每类自有池纪律——不共用公共 ForkJoinPool）；</li>
 *   <li>完成自移除：leader complete/completeExceptionally 两终态均触发
 *       whenComplete 出表，杜绝长期泄漏（HedgeInFlightRegistry 同款）。</li>
 * </ul>
 *
 * <p>开关 travel.rag.query-coalesce.enabled 默认 false（E-33）：关闭时
 * {@link #coalesce} 直通 {@code computation.get()}，行为与无协衡现状逐字节等价。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Component
public class QueryCoalescer {

    /** cacheKey → 在飞协衡 future（leader 手动 complete/completeExceptionally）。 */
    private final ConcurrentMap<String, CompletableFuture<List<SearchResult>>> inFlight =
            new ConcurrentHashMap<>();

    private final boolean enabled;

    public QueryCoalescer(
            @Value("${travel.rag.query-coalesce.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 协衡执行：键空闲则成为 leader 同步计算；键在飞则 join 共享结果
     * （join 失败降级为自身直搜，P0⑳）；开关关闭直通。
     *
     * @param key         协衡键（与 QueryTtlCache key 同源，P0⑲）
     * @param computation 底层计算（允许抛运行时异常）
     * @return 计算结果（leader/joiner/直通三态同一返回契约）
     */
    public List<SearchResult> coalesce(String key, Supplier<List<SearchResult>> computation) {
        if (!enabled) {
            return computation.get();
        }
        CompletableFuture<List<SearchResult>> leader = new CompletableFuture<>();
        CompletableFuture<List<SearchResult>> existing = inFlight.putIfAbsent(key, leader);
        if (existing != null) {
            // joiner：同 key 在飞——共享 leader 结果；leader 失败→降级直搜（P0⑳）
            try {
                return existing.join();
            } catch (CompletionException e) {
                return computation.get();
            }
        }
        leader.whenComplete((result, error) -> inFlight.remove(key));
        try {
            List<SearchResult> result = computation.get();
            leader.complete(result);
            return result;
        } catch (RuntimeException | Error e) {
            leader.completeExceptionally(e);
            throw e;
        }
    }

    /** 当前在飞键数（观测/测试断言用）。 */
    public int size() {
        return inFlight.size();
    }
}
