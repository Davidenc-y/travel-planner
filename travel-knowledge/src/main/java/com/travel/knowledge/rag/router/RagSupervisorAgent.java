package com.travel.knowledge.rag.router;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * RAG 多步组合 Supervisor（F43/P2.5）。
 *
 * <p>SupervisorAgent 动态编排四个策略子 Agent（hybrid/corrective/self/naive），
 * 实现"先 hybrid 再 corrective/self"的多步组合检索；最终输出为最后一次
 * 策略工具返回的 JSON 数组。</p>
 *
 * <p>可靠性：墙钟超时 30s + recursionLimit=12 + mainAgent 空 SaverConfig（F26 模式）；
 * 任何失败返回 null，由 RagDispatcher 依次回退单步 Agent / 启发式路由。</p>
 */
@Slf4j
@Component
@SuppressWarnings("deprecation")
public class RagSupervisorAgent {

    private static final long TIMEOUT_SECONDS = 30;
    private static final int RECURSION_LIMIT = 12;
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final ChatModel chatModel;
    private final RagStrategyToolProvider toolProvider;
    private SupervisorAgent supervisor;

    public RagSupervisorAgent(ChatModel chatModel, RagStrategyToolProvider toolProvider) {
        this.chatModel = chatModel;
        this.toolProvider = toolProvider;
    }

    @PostConstruct
    public void init() {
        try {
            ReactAgent mainAgent = ReactAgent.builder()
                    .name("rag_supervisor_main")
                    .model(chatModel)
                    .description("RAG 多步组合检索监督者，负责路由决策")
                    .systemPrompt("""
                            你是 RAG 多步组合检索监督者。可用的子 Agent：
                            - hybrid_agent(混合检索): BM25+KNN+RRF 融合，通用首选
                            - corrective_agent(纠错重写): 初始结果质量差时重写查询并二次检索合并
                            - self_rag_agent(自适应过滤): 需要过滤低置信结果时使用
                            - naive_agent(文本检索): 简单关键词快速检索

                            路由决策输出 JSON 数组（如 ["hybrid_agent"]），任务完成输出 [] 或 ["FINISH"]。
                            组合策略：通常先 hybrid_agent；若结果少于 3 条或摘要过短，再 corrective_agent；
                            需要过滤低置信结果时用 self_rag_agent。合法元素仅限四个子 Agent 名与 FINISH。
                            """)
                    .instruction("用户的检索意图是: {input}")
                    .outputKey("final_output")
                    // F26 模式：mainAgent 子图空 SaverConfig，避免状态跨调用累积。
                    .compileConfig(CompileConfig.builder().saverConfig(new SaverConfig()).build())
                    .build();

            this.supervisor = SupervisorAgent.builder()
                    .name("rag_composition_supervisor")
                    .model(chatModel)
                    .mainAgent(mainAgent)
                    .subAgents(List.of(
                            strategyAgent("hybrid_agent", "hybrid_search",
                                    "混合检索（BM25+KNN+RRF），通用首选", "hybrid_result"),
                            strategyAgent("corrective_agent", "corrective_rag_search",
                                    "纠错重写检索：初始结果质量差时二次检索合并", "corrective_result"),
                            strategyAgent("self_rag_agent", "self_rag_search",
                                    "自适应过滤检索：按置信度过滤低质量结果", "self_result"),
                            strategyAgent("naive_agent", "naive_search",
                                    "文本检索：简单关键词快速检索", "naive_result")))
                    // F26 模式：父图保留默认 saver，仅限制循环迭代上限。
                    .compileConfig(CompileConfig.builder().recursionLimit(RECURSION_LIMIT).build())
                    .build();
            log.info("RagSupervisorAgent 初始化完成");
        } catch (Exception e) {
            log.error("RagSupervisorAgent 初始化失败，将回退单步 Agent/启发式路由: {}", e.getMessage());
        }
    }

    /**
     * 多步组合检索入口；失败/超时返回 null（由调用方回退）
     */
    public List<SearchResult> route(QueryIntent intent, int topK) {
        if (supervisor == null) {
            return null;
        }
        try {
            String input = buildInput(intent, topK);
            CompletableFuture<Optional<OverAllState>> future = CompletableFuture.supplyAsync(
                    () -> invokeSafely(supervisor, input), EXECUTOR);
            OverAllState state = future.orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .get()
                    .orElse(null);
            if (state == null) {
                return null;
            }
            String text = lastMeaningfulOutput(state);
            String json = RagJsonExtractor.extract(text);
            if (json == null) {
                log.warn("[RagSupervisor] 输出未包含有效 JSON，回退单步 Agent");
                return null;
            }
            List<SearchResult> results = JsonUtils.fromJson(json, new TypeReference<List<SearchResult>>() {
            });
            log.info("[RagSupervisor] 多步组合完成, 结果 {} 条", results == null ? 0 : results.size());
            return results;
        } catch (Exception e) {
            log.warn("[RagSupervisor] 执行失败，回退单步 Agent/启发式: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 构建策略子 Agent：各绑定一个策略工具，输出必须原样回传工具 JSON 数组
     */
    private ReactAgent strategyAgent(String name, String toolName, String description, String outputKey) {
        return ReactAgent.builder()
                .name(name)
                .model(chatModel)
                .description(description)
                .systemPrompt("你是" + description + "执行 Agent。")
                .instruction("""
                        根据上下文中的查询意图调用 %s 工具执行检索。
                        调用后必须原样输出工具返回的 JSON 数组，不要添加任何其他内容。
                        """.formatted(toolName))
                .tools(List.of(toolProvider.toolByName(toolName)))
                .outputKey(outputKey)
                .build();
    }

    private static Optional<OverAllState> invokeSafely(SupervisorAgent supervisor, String input) {
        try {
            // M8-1（F51 同构修复）：每次调用使用唯一 threadId，父图 checkpoint 按调用隔离。
            // 否则父图默认 MemorySaver + 固定默认 threadId 会跨调用累积 state，
            // 上一查询的子 Agent 输出（hybrid_result 等）被后续查询复用——
            // 实证：RAG 评测中 auto 路径 Q044/Q045 返回 Q043 的陈旧结果、
            // 或主代理在污染上下文中直接 FINISH 导致 0 条（chat 域 F51 同型已修）。
            return supervisor.invoke(input, RunnableConfig.builder()
                    .threadId("rag_supervisor_" + UUID.randomUUID())
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("RagSupervisor 执行失败", e);
        }
    }

    /**
     * 从最终 state 取最后一次策略输出：优先四个 outputKey（corrective > self > hybrid > naive），
     * 兜底取 messages 中最后一条非路由决策的 AssistantMessage。
     */
    private static String lastMeaningfulOutput(OverAllState state) {
        for (String key : List.of("corrective_result", "self_result", "hybrid_result", "naive_result")) {
            String v = toText(state.value(key));
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        Object messages = state.value("messages").orElse(null);
        if (messages instanceof List<?> list) {
            for (int i = list.size() - 1; i >= 0; i--) {
                Object m = list.get(i);
                if (!(m instanceof AssistantMessage am)) {
                    continue;
                }
                String text = am.getText();
                if (text == null || text.isBlank() || looksLikeRoutingDecision(text)) {
                    continue;
                }
                return text;
            }
        }
        return "";
    }

    /** 路由决策形如 ["agent"] / ["FINISH"] / [] / FINISH，不当作最终输出。 */
    private static boolean looksLikeRoutingDecision(String text) {
        String t = text.trim();
        if ("FINISH".equalsIgnoreCase(t) || "[]".equals(t)) {
            return true;
        }
        return t.startsWith("[") && t.endsWith("]") && !t.contains("\"");
    }

    /** 安全提取 state 值：递归解包 Optional，兼容 AssistantMessage/String。 */
    private static String toText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Optional<?> opt) {
            return toText(opt.orElse(null));
        }
        if (value instanceof AssistantMessage am) {
            return am.getText();
        }
        return value.toString();
    }

    private static String buildInput(QueryIntent intent, int topK) {
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
