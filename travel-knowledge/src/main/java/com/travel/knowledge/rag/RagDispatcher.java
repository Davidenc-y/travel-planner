package com.travel.knowledge.rag;

import com.travel.common.exception.RagRetrievalException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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

    @Value("${travel.rag.default-type:hybrid}")
    private String defaultType;

    /**
     * Spring 自动注入所有 RagStrategy 实现，key 为 Bean 名。
     *
     * <p>Bean 名映射：naiveRag → naive, hybridRag → hybrid, selfRag → self_rag, correctiveRag → corrective_rag</p>
     */
    public RagDispatcher(Map<String, RagStrategy> strategies) {
        this.strategies = strategies;
        log.info("RagDispatcher 初始化, 已注册策略: {}", strategies.keySet());
    }

    /**
     * 执行 RAG 检索
     *
     * @param ragType 策略类型（naive / hybrid / self_rag / corrective_rag），null 则用默认
     * @param query   用户查询
     * @param topK    返回结果数
     * @return 检索结果列表
     */
    public List<SearchResult> dispatch(String ragType, String query, int topK) {
        String type = (ragType == null || ragType.isBlank()) ? defaultType : ragType.toLowerCase();

        // Bean 名映射：type → beanName（首字母大写 + "Rag"）
        String beanName = type + "Rag";
        RagStrategy strategy = strategies.get(beanName);

        if (strategy == null) {
            log.warn("未找到 RAG 策略: {}, 回退到默认: {}", type, defaultType);
            strategy = strategies.get(defaultType + "Rag");
        }

        if (strategy == null) {
            throw new RagRetrievalException("未找到 RAG 策略: " + type + " (默认: " + defaultType + ")");
        }

        log.info("[RagDispatcher] type={}, strategy={}", type, strategy.getClass().getSimpleName());
        return strategy.retrieve(query, topK);
    }

    /**
     * 使用默认策略检索
     */
    public List<SearchResult> dispatch(String query, int topK) {
        return dispatch(defaultType, query, topK);
    }

    /**
     * 获取已注册的策略列表
     */
    public Map<String, RagStrategy> getStrategies() {
        return strategies;
    }
}
