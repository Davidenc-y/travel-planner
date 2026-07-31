package com.travel.knowledge.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Self-RAG 策略（自适应检索）
 *
 * <p>借鉴开源 Travel-Agent 的 Self-RAG 思想：</p>
 * <ol>
 *   <li>Step 1: LLM 判断是否需要检索（闲聊/打招呼 → 跳过检索）</li>
 *   <li>Step 2: 执行 HybridRAG 混合检索</li>
 *   <li>Step 3: 评估相关性，过滤低质量结果</li>
 * </ol>
 *
 * <p>需要 ChatModel 做意图判断，使用注入的 ChatModel（由 knowledge 模块的
 * spring-ai-alibaba-starter-dashscope 自动配置）。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Component("selfRag")
public class SelfRagStrategy implements RagStrategy {

    /** RRF 得分阈值，低于此值视为低质量 */
    private static final double SCORE_THRESHOLD = 0.001;

    private final HybridRagStrategy hybridStrategy;
    private final ChatModel chatModel;

    @Autowired
    public SelfRagStrategy(HybridRagStrategy hybridStrategy, ChatModel chatModel) {
        this.hybridStrategy = hybridStrategy;
        this.chatModel = chatModel;
    }

    @Override
    public List<SearchResult> retrieve(String query, int topK) {
        log.info("[SelfRAG] query={}", query);

        // Step 1: 判断是否需要检索
        if (!shouldRetrieve(query)) {
            log.info("[SelfRAG] 模型判定无需检索，返回空列表");
            return List.of();
        }

        // Step 2: 执行混合检索
        List<SearchResult> results = hybridStrategy.retrieve(query, topK);

        // Step 3: 过滤低质量结果
        List<SearchResult> filtered = results.stream()
                .filter(r -> r.getScore() > SCORE_THRESHOLD)
                .toList();

        log.info("[SelfRAG] 检索 {} 条, 过滤后 {} 条", results.size(), filtered.size());
        return filtered;
    }

    /**
     * LLM 判断是否需要检索
     *
     * <p>规则：询问具体景点/路线/酒店 → YES；闲聊/打招呼 → NO</p>
     */
    private boolean shouldRetrieve(String query) {
        try {
            String prompt = String.format("""
                    判断以下查询是否需要检索旅游知识库（回答 YES 或 NO）：
                    查询：%s

                    规则：
                    - 询问具体景点、路线、酒店、美食 → YES
                    - 闲聊、打招呼、问天气常识 → NO
                    只回答 YES 或 NO。
                    """, query);
            String response = chatModel.call(prompt);
            boolean should = response != null && response.trim().toUpperCase().startsWith("Y");
            log.debug("[SelfRAG] shouldRetrieve={}, response={}", should, response);
            return should;
        } catch (Exception e) {
            log.warn("[SelfRAG] LLM 判断失败，默认需要检索: {}", e.getMessage());
            return true;  // 兜底：LLM 失败时默认检索
        }
    }

    @Override
    public String getType() {
        return "self_rag";
    }
}
