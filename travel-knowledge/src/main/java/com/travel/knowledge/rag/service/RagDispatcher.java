package com.travel.knowledge.rag.service;

import com.travel.common.exception.RagRetrievalException;
import com.travel.common.util.JsonUtils;
import com.travel.knowledge.rag.model.QueryIntent;
import com.travel.knowledge.rag.model.SearchResult;
import com.travel.knowledge.rag.router.AutoRagRouterAgent;
import com.travel.knowledge.rag.router.RagSupervisorAgent;
import com.travel.knowledge.rag.strategy.RagStrategy;
import com.travel.knowledge.rag.support.HedgeInFlightRegistry;
import com.travel.knowledge.rag.support.QueryTtlCache;
import com.travel.knowledge.rag.support.RagRoutingMetrics;
import com.travel.common.trace.SpanCollector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * RAG 策略调度器
 *
 * <p>通过 ragType 参数路由到对应的 RAG 策略实现。</p>
 *
 * <p>4 种策略：</p>
 * <ul>
 *   <li>naive: 单路 BM25（NaiveRagStrategy）</li>
 *   <li>hybrid: BM25+KNN+RRF 混合检索（HybridRagStrategy，默认）</li>
 *   <li>self_rag: 自适应检索（SelfRagStrategy）</li>
 *   <li>corrective_rag: 查询重写（CorrectiveRagStrategy）</li>
 * </ul>
 *
 * <p>借鉴开源 Travel-Agent 的 RAGDispatcher 思想。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Component
public class RagDispatcher {

    private final Map<String, RagStrategy> strategies;
    private final AutoRagRouterAgent autoRagRouterAgent;
    private final RagSupervisorAgent ragSupervisorAgent;
    private final RagRoutingMetrics metrics;
    private final QueryTtlCache queryTtlCache;

    @Value("${travel.rag.default-type:hybrid}")
    private String defaultType;

    /** P3：auto 复杂查询首选路由（supervisor / agent / heuristic），A/B 用 */
    @Value("${travel.rag.auto-router:supervisor}")
    private String autoRouterMode;

    /** S-B2b（L1c）：对冲单飞注册表（optional 注入，bean 缺省=预启关闭，构造签名零变更——MR-D2 先例） */
    private HedgeInFlightRegistry hedgeRegistry;

    /** S-B2b：hedge-enabled 默认 false=预启关闭（关闭态逐字节等价，E-33） */
    @Value("${travel.rag.hedge-enabled:false}")
    private boolean hedgeEnabled;

    /** S-B2b：预启专用虚拟线程池（F46：与两 router 同 idiom，每类自有池，不共用公共 ForkJoinPool） */
    private static final ExecutorService HEDGE_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /** S-B2b：预启预算（P0⑫ 红线 <10s；对齐 supervisor 执行层 8s） */
    private static final long HEDGE_BUDGET_SECONDS = 8;

    /** S-B2b：optional 注入对冲协作件（B-1 注册表 bean） */
    @Autowired(required = false)
    void setHedgeRegistry(HedgeInFlightRegistry hedgeRegistry) {
        this.hedgeRegistry = hedgeRegistry;
    }

    /** S-B5a：Span 采集（optional 注入，缺省自给实例=未绑定时挂点全空安全跳过） */
    private SpanCollector spanCollector = new SpanCollector();

    @Autowired(required = false)
    void setSpanCollector(SpanCollector spanCollector) {
        this.spanCollector = spanCollector;
    }

    /**
     * S-B2b（L1c）：进入路由层预启 heuristic→hybrid 对冲 future——发射后不管：
     * 收割=策略侧单飞 join（B-2a，HybridRagStrategy.doRetrieve register-or-join 同键去重），
     * 本方法不注册不缓存（QueryTtlCache 契约=仅 dispatch 返回值写入）。
     * F23：orTimeout 超时路径 cancel(true)；预启失败静默（join 者有内联重取兜底）。
     *
     * @return true=预启已发射（供 router=hedged 标签判定）
     */
    private CompletableFuture<List<SearchResult>> maybePrefetchHedge(QueryIntent intent, int topK) {
        if (!hedgeEnabled || hedgeRegistry == null) {
            return null;
        }
        RagStrategy hybrid = strategies.get("hybridRag");
        if (hybrid == null) {
            return null;
        }
        CompletableFuture<List<SearchResult>> future = CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return hybrid.retrieve(intent, topK);
                    } catch (Exception e) {
                        // 预启失败静默：收割方（B-2a join 失败）自带内联重取兜底
                        return null;
                    }
                }, HEDGE_EXECUTOR);
        future.orTimeout(HEDGE_BUDGET_SECONDS, TimeUnit.SECONDS);
        return future;
    }

    /**
     * Spring 自动注入所有 RagStrategy 实现，key 为 Bean 名。
     *
     * <p>Bean 名：naiveRag / hybridRag / selfRag / correctiveRag；
     * dispatch 按显式类型→Bean 名映射路由（F36/K1）。</p>
     */
    public RagDispatcher(Map<String, RagStrategy> strategies,
                         AutoRagRouterAgent autoRagRouterAgent,
                         RagSupervisorAgent ragSupervisorAgent,
                         RagRoutingMetrics metrics,
                         QueryTtlCache queryTtlCache) {
        this.strategies = strategies;
        this.autoRagRouterAgent = autoRagRouterAgent;
        this.ragSupervisorAgent = ragSupervisorAgent;
        this.metrics = metrics;
        this.queryTtlCache = queryTtlCache;
        log.info("RagDispatcher 初始化, 已注册策略: {}", strategies.keySet());
    }

    /**
     * 执行 RAG 检索（F40/P1）
     *
     * @param ragType 策略类型（naive / hybrid / self_rag / corrective_rag）；
     *                null / 空 / "auto" 走启发式路由
     * @param intent  结构化查询意图
     * @param topK    返回结果数
     * @return 检索结果列表
     */
    public List<SearchResult> dispatch(String ragType, QueryIntent intent, int topK) {
        // B1.2：查询级 TTL 缓存——命中且未过期直接返回，miss 走原逻辑后写入；
        // 开关关闭时 get/put 双直通（行为回到无缓存现状）。
        String cacheKey = QueryTtlCache.buildKey(ragType, intent, topK);
        List<SearchResult> cached = queryTtlCache.get(cacheKey);
        if (cached != null) {
            log.debug("[RagCache] hit key={}", cacheKey);
            return cached;
        }
        List<SearchResult> result = doDispatch(ragType, intent, topK);
        queryTtlCache.put(cacheKey, result);
        return result;
    }

    private List<SearchResult> doDispatch(String ragType, QueryIntent intent, int topK) {
        long start = System.currentTimeMillis();
        String type = normalizeType(ragType);
        String router = "explicit";
        String strategyName = type;
        List<SearchResult> result = null;
        // S-B5a：路由层 span（上下文未绑定时空安全跳过——单测/直调路径零影响）
        SpanCollector.Span routingSpan = spanCollector.start("route", "routing");
        boolean hedgeLaunched = false;
        // S 审计修复：收割变量提升到外层作用域（heuristic 兜底分支需访问）
        java.util.concurrent.CompletableFuture<List<SearchResult>> hedgeFuture = null;
        long deadline = 0L;

        if ("auto".equals(type)) {
            // F43/P2.5 + F44/P3：复杂查询按 auto-router 模式依次尝试（A/B），最后启发式兜底。
            if (isComplex(intent)) {
                // S-B2b（L1c）：进入路由层即预启 heuristic→hybrid 对冲（发射后不管，收割=策略侧单飞
                // join，B-2a）；预启直调 retrieve 不走 dispatch——QueryTtlCache 契约"仅返回值写缓存"不变。
                hedgeFuture = maybePrefetchHedge(intent, topK);
                hedgeLaunched = hedgeFuture != null;
                // S 审计修复（2026-09-21）：8s 为路由层总预算（原实现两层串行 6+8s 破预算）；
                // 预启 future 由 dispatcher 持有并在预算内显式收割（原 fire-and-forget + registry
                // 即刻逐出=跑完的结果不可收割，实测 15.6s 未命中对冲）
                deadline = System.currentTimeMillis() + HEDGE_BUDGET_SECONDS * 1000L;
                for (String mode : attemptOrder()) {
                    if (System.currentTimeMillis() >= deadline) {
                        break;
                    }
                    if ("supervisor".equals(mode)) {
                        result = ragSupervisorAgent.route(intent, topK);
                        if (result != null) {
                            router = "supervisor";
                            strategyName = "supervisor";
                            break;
                        }
                    } else {
                        result = autoRagRouterAgent.route(intent, topK);
                        if (result != null) {
                            router = "llm";
                            strategyName = "agent";
                            break;
                        }
                    }
                }
            }
            if (result == null && hedgeLaunched) {
                // S 审计修复：预算内收割对冲 future（超时/异常→cancel(true) F23，交由 heuristic 兜底）
                long remaining = deadline - System.currentTimeMillis();
                log.info("[HedgeHarvest] 进入收割: remaining={}ms done={}", remaining, hedgeFuture.isDone());
                if (remaining > 0) {
                    try {
                        List<SearchResult> harvested = hedgeFuture.get(remaining, TimeUnit.MILLISECONDS);
                        log.info("[HedgeHarvest] 收割返回: size={}", harvested == null ? -1 : harvested.size());
                        if (harvested != null && !harvested.isEmpty()) {
                            result = harvested;
                            router = "hedged";
                            strategyName = "hybrid";
                        }
                    } catch (Exception e) {
                        log.warn("[HedgeHarvest] 收割异常: {}", String.valueOf(e));
                        hedgeFuture.cancel(true);
                    }
                } else {
                    log.warn("[HedgeHarvest] 预算耗尽取消对冲");
                    hedgeFuture.cancel(true);
                }
            }
            if (result == null) {
                strategyName = heuristicRoute(intent);
                router = "heuristic";
                result = dispatchByName(strategyName, intent, topK);
                // S-B2b：对冲胜出——预启已发射且兜底命中 hybrid（收割或重取均为对冲策略生效），
                // router=hedged 标签（E-47 §八⑥ 授权新增）；metrics 仍单次记录。
                if (hedgeLaunched && "hybrid".equals(strategyName) && result != null) {
                    router = "hedged";
                }
            }
        } else {
            result = dispatchByName(type, intent, topK);
        }

        if (routingSpan != null) {
            Map<String, Object> attrs = new java.util.LinkedHashMap<>();
            attrs.put("ragType", type);
            attrs.put("router", router);
            attrs.put("strategy", strategyName);
            attrs.put("hedgeLaunched", hedgeLaunched);
            spanCollector.end(routingSpan, "ok", attrs);
        }
        metrics.record(router, strategyName, System.currentTimeMillis() - start);
        // F43/P2.5：单条结构化路由日志（意图快照 + 路由方式 + 策略 + 耗时 + 结果数）。
        log.info("[RagRouting] intent={} router={} strategy={} elapsedMs={} resultCount={}",
                JsonUtils.toJson(intent), router, strategyName,
                System.currentTimeMillis() - start, result == null ? 0 : result.size());
        return result;
    }

    /**
     * P3：auto 复杂查询的尝试顺序（由 travel.rag.auto-router 决定，A/B 用）
     */
    private List<String> attemptOrder() {
        String mode = autoRouterMode == null ? "supervisor" : autoRouterMode.toLowerCase();
        // S 审计修复：单层化——hedge 收割承担兜底（原 agent+supervisor 两层串行 6+8s 超 8s 总预算）
        return switch (mode) {
            case "agent" -> List.of("agent");
            case "heuristic" -> List.of();
            default -> List.of("supervisor");
        };
    }

    /**
     * 是否值得走 LLM 路由（复杂/多意图判断，F42/P2）
     */
    private boolean isComplex(QueryIntent intent) {
        if (intent == null) {
            return false;
        }
        return StringUtils.hasText(intent.city())
                || StringUtils.hasText(intent.type())
                || intent.freeOnly()
                || (intent.keywords() != null && intent.keywords().size() > 2)
                || (intent.rawQuery() != null && intent.rawQuery().length() > 12);
    }

    private String normalizeType(String ragType) {
        if (ragType == null || ragType.isBlank() || "auto".equalsIgnoreCase(ragType)) {
            return "auto";
        }
        return ragType.toLowerCase();
    }

    /**
     * auto 启发式路由（P1）：
     * 含结构化约束（城市/类型/免费）→ hybrid；纯简单关键词（≤2 个）→ naive；其余 hybrid。
     * S-A1/A-3（naive 补门控）：simple 分支 naive→hybrid——短离域查询同样过 rerank+gate，
     * 不再注入无低置信标记的垃圾卡（原 naive 单路 BM25 绕开门控实证，20260920 三进程日志 P2-④）。
     */
    private String heuristicRoute(QueryIntent intent) {
        return "hybrid";
    }

    private List<SearchResult> dispatchByName(String type, QueryIntent intent, int topK) {
        // F36/K1：显式类型 → Bean 名映射，避免 self_rag → self_ragRag 拼错导致静默回退 hybrid。
        String beanName = toBeanName(type);
        RagStrategy strategy = strategies.get(beanName);

        if (strategy == null) {
            log.warn("未找到 RAG 策略: {}, 回退到默认: {}", type, defaultType);
            strategy = strategies.get(toBeanName(defaultType));
        }

        if (strategy == null) {
            throw new RagRetrievalException("未找到 RAG 策略: " + type + " (默认: " + defaultType + ")");
        }

        log.info("[RagDispatcher] type={}, strategy={}", type, strategy.getClass().getSimpleName());
        return strategy.retrieve(intent, topK);
    }

    /**
     * ragType → Spring Bean 名显式映射。
     * naive→naiveRag、hybrid→hybridRag、self_rag→selfRag、corrective_rag→correctiveRag；
     * 其余未知类型保持 type+"Rag"（查不到时走回退默认逻辑）。
     */
    private String toBeanName(String type) {
        if ("self_rag".equals(type)) {
            return "selfRag";
        }
        if ("corrective_rag".equals(type)) {
            return "correctiveRag";
        }
        return type + "Rag";
    }

    /**
     * 获取已注册的策略列表
     */
    public Map<String, RagStrategy> getStrategies() {
        return strategies;
    }
}
