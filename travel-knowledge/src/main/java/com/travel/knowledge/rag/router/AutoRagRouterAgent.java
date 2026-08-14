package com.travel.knowledge.rag.router;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.travel.common.util.JsonUtils;
import com.travel.knowledge.rag.support.RagJsonExtractor;
import com.travel.knowledge.rag.model.QueryIntent;
import com.travel.knowledge.rag.model.SearchResult;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 自动路由 Agent（F42/P2，Agentic RAG 路由）。
 *
 * <p>针对复杂/多意图查询：ReactAgent 依据 QueryIntent 与四个策略工具的描述，
 * 选择并调用最合适的策略工具；工具返回 JSON 数组，Agent 被要求原样回传，
 * 本类解析回 {@link SearchResult} 列表。</p>
 *
 * <p>可靠性设计：初始化失败或调用超时（20s）/解析失败时返回 null，
 * 由 RagDispatcher 回退启发式路由，保证 auto 模式始终可用。</p>
 */
@Slf4j
@Component
public class AutoRagRouterAgent {

    private static final long TIMEOUT_SECONDS = 20;
    /** F46：与 RagSupervisorAgent 一致使用虚拟线程，避免公共 ForkJoinPool 争用 */
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final ChatModel chatModel;
    private final RagStrategyToolProvider toolProvider;
    private ReactAgent agent;

    public AutoRagRouterAgent(ChatModel chatModel, RagStrategyToolProvider toolProvider) {
        this.chatModel = chatModel;
        this.toolProvider = toolProvider;
    }

    @PostConstruct
    public void init() {
        try {
            this.agent = ReactAgent.builder()
                    .name("rag_auto_router")
                    .description("复杂查询自动选择最合适的 RAG 检索策略")
                    .model(chatModel)
                    .systemPrompt("你是旅游检索路由 Agent：根据查询意图从四个检索工具中选择最合适的并调用。")
                    .instruction("""
                            根据给定的查询意图 JSON，选择并调用最合适的检索工具。

                            选择原则：
                            - 没有明确关键词/需要语义扩展 → hybrid_search
                            - 简单关键词短查询 → naive_search
                            - 需要先判断查询是否有效再检索 → self_rag_search
                            - 城市/类型明确的查询优先 hybrid_search（工具会自动按 city/type 过滤）

                            调用工具后，最终回答必须原样输出工具返回的 JSON 数组，不要添加任何前缀、后缀或解释。
                            """)
                    .tools(toolProvider.toolCallbacks())
                    .build();
            log.info("AutoRagRouterAgent 初始化完成，已注册 {} 个策略工具", toolProvider.toolCallbacks().size());
        } catch (Exception e) {
            log.error("AutoRagRouterAgent 初始化失败，auto 将回退启发式路由: {}", e.getMessage());
        }
    }

    /**
     * Agent 路由入口；失败/超时返回 null（由调用方回退）
     */
    public List<SearchResult> route(QueryIntent intent, int topK) {
        if (agent == null) {
            return null;
        }
        try {
            String input = buildInput(intent, topK);
            // GraphRunnerException 为受检异常，lambda 内包装为 RuntimeException 再统一兜底。
            AssistantMessage message = CompletableFuture.supplyAsync(() -> {
                        try {
                            return agent.call(input);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }, EXECUTOR)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            String text = message == null ? null : message.getText();
            String json = RagJsonExtractor.extract(text);
            if (json == null) {
                log.warn("[AutoRagRouter] Agent 输出未包含有效 JSON，回退启发式");
                return null;
            }
            List<SearchResult> results = JsonUtils.fromJson(json, new TypeReference<List<SearchResult>>() {
            });
            if (results == null) {
                return null;
            }
            log.info("[AutoRagRouter] Agent 路由成功, 结果 {} 条", results.size());
            return results;
        } catch (Exception e) {
            log.warn("[AutoRagRouter] Agent 路由失败，回退启发式: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 把 QueryIntent 序列化为 Agent 输入（含 topK）
     */
    private String buildInput(QueryIntent intent, int topK) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("query", intent.rawQuery());
        m.put("city", intent.city());
        m.put("type", intent.type());
        m.put("keywords", intent.keywords());
        m.put("freeOnly", intent.freeOnly());
        m.put("topK", topK);
        return JsonUtils.toJson(m);
    }

}
