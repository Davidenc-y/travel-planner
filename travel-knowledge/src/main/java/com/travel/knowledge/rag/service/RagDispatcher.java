package com.travel.knowledge.rag.service;

import com.travel.common.exception.RagRetrievalException;
import com.travel.common.util.JsonUtils;
import com.travel.knowledge.rag.model.QueryIntent;
import com.travel.knowledge.rag.model.SearchResult;
import com.travel.knowledge.rag.router.AutoRagRouterAgent;
import com.travel.knowledge.rag.router.RagSupervisorAgent;
import com.travel.knowledge.rag.strategy.RagStrategy;
import com.travel.knowledge.rag.support.RagRoutingMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

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

    @Value("${travel.rag.default-type:hybrid}")
    private String defaultType;

    /** P3：auto 复杂查询首选路由（supervisor / agent / heuristic），A/B 用 */
    @Value("${travel.rag.auto-router:supervisor}")
    private String autoRouterMode;

    /**
     * Spring 自动注入所有 RagStrategy 实现，key 为 Bean 名。
     *
     * <p>Bean 名：naiveRag / hybridRag / selfRag / correctiveRag；
     * dispatch 按显式类型→Bean 名映射路由（F36/K1）。</p>
     */
    public RagDispatcher(Map<String, RagStrategy> strategies,
                         AutoRagRouterAgent autoRagRouterAgent,
                         RagSupervisorAgent ragSupervisorAgent,
                         RagRoutingMetrics metrics) {
        this.strategies = strategies;
        this.autoRagRouterAgent = autoRagRouterAgent;
        this.ragSupervisorAgent = ragSupervisorAgent;
        this.metrics = metrics;
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
        long start = System.currentTimeMillis();
        String type = normalizeType(ragType);
        String router = "explicit";
        String strategyName = type;
        List<SearchResult> result = null;

        if ("auto".equals(type)) {
            // F43/P2.5 + F44/P3：复杂查询按 auto-router 模式依次尝试（A/B），最后启发式兜底。
            if (isComplex(intent)) {
                for (String mode : attemptOrder()) {
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
            if (result == null) {
                strategyName = heuristicRoute(intent);
                router = "heuristic";
                result = dispatchByName(strategyName, intent, topK);
            }
        } else {
            result = dispatchByName(type, intent, topK);
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
        return switch (mode) {
            case "agent" -> List.of("agent", "supervisor");
            case "heuristic" -> List.of();
            default -> List.of("supervisor", "agent");
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
     */
    private String heuristicRoute(QueryIntent intent) {
        boolean structured = intent != null && (StringUtils.hasText(intent.city())
                || StringUtils.hasText(intent.type()) || intent.freeOnly());
        boolean simple = !structured && (intent == null
                || intent.keywords() == null || intent.keywords().size() <= 2);
        return simple ? "naive" : "hybrid";
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
