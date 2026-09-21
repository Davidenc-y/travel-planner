package com.travel.knowledge.rag.router;

import com.travel.common.util.JsonUtils;
import com.travel.knowledge.rag.model.QueryIntent;
import com.travel.knowledge.rag.model.RagToolRequest;
import com.travel.knowledge.rag.model.SearchResult;
import com.travel.knowledge.rag.strategy.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RAG 策略工具集（F42/P2）。
 *
 * <p>把四个检索策略封装为 {@link ToolCallback}，供 Agent 路由（Agentic RAG）调用；
 * 每个工具执行对应策略的 retrieve 并返回 JSON 数组结果。</p>
 */
@Slf4j
@Component
public class RagStrategyToolProvider {

    private final NaiveRagStrategy naiveStrategy;
    private final HybridRagStrategy hybridStrategy;
    private final SelfRagStrategy selfRagStrategy;
    private final CorrectiveRagStrategy correctiveRagStrategy;

    public RagStrategyToolProvider(NaiveRagStrategy naiveStrategy,
                                   HybridRagStrategy hybridStrategy,
                                   SelfRagStrategy selfRagStrategy,
                                   CorrectiveRagStrategy correctiveRagStrategy) {
        this.naiveStrategy = naiveStrategy;
        this.hybridStrategy = hybridStrategy;
        this.selfRagStrategy = selfRagStrategy;
        this.correctiveRagStrategy = correctiveRagStrategy;
    }

    /**
     * 四个策略工具（description 是 Agent 路由决策的关键依据）
     */
    public List<ToolCallback> toolCallbacks() {
        return List.of(
                tool("naive_search",
                        "关键词明确、短查询、只需快速文本检索时使用；仅 ES BM25，速度最快。",
                        naiveStrategy),
                tool("hybrid_search",
                        "通用推荐策略：BM25 文本 + 向量 KNN + RRF 融合，质量最高，没有特殊偏好时默认使用。",
                        hybridStrategy),
                tool("self_rag_search",
                        "需要先判断查询是否值得检索（闲聊/无效查询时跳过），再混合检索并按置信度过滤时使用。",
                        selfRagStrategy),
                tool("corrective_rag_search",
                        "初始检索质量差（结果少或摘要过短）时，用 LLM 重写查询并二次检索合并时使用。",
                        correctiveRagStrategy));
    }

    /**
     * 按工具名取单个策略工具（F43/P2.5：子 Agent 各绑定一个工具）
     */
    public ToolCallback toolByName(String name) {
        return toolCallbacks().stream()
                .filter(t -> name.equals(t.getToolDefinition().name()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到 RAG 策略工具: " + name));
    }

    private ToolCallback tool(String name, String description, RagStrategy strategy) {
        return FunctionToolCallback.builder(name, (RagToolRequest req, ToolContext ctx) -> execute(strategy, req))
                .description(description)
                .inputType(RagToolRequest.class)
                .build();
    }

    /**
     * 执行策略并返回 JSON 数组（供 Agent 原样回传）。
     * S-C3：异常不再裸抛（框架层无差别失败），改为结构化错误对象
     * {@code {code,type,hint}}——模型可区分限流（重试）与参数错（改参）。
     */
    private String execute(RagStrategy strategy, RagToolRequest req) {
        try {
            // F46：透传 keywords，保证 agent/supervisor 路径与启发式路径的意图快照一致。
            QueryIntent intent = new QueryIntent(req.city(), req.type(), req.keywords(), req.freeOnly(), req.query());
            List<SearchResult> results = strategy.retrieve(intent, req.topK());
            return JsonUtils.toJson(results);
        } catch (Exception e) {
            return JsonUtils.toJson(classify(e));
        }
    }

    /** S-C3：结构化工具错误（type∈retryable/param/permission/not_found；博客 1.5 四分类）。 */
    record ToolError(String code, String type, String hint) {
    }

    static ToolError classify(Throwable e) {
        String message = e == null ? "" : String.valueOf(e.getMessage()).toLowerCase();
        if (e instanceof IllegalArgumentException
                || (e instanceof com.travel.common.exception.BusinessException be && be.getCode() / 1000 == 40 && be.getCode() % 1000 < 500 && be.getCode() < 40300)) {
            return new ToolError("TOOL_PARAM_INVALID", "param",
                    "参数不合法：请检查 query/topK/city/type 后修正重试");
        }
        if (message.contains("限流") || message.contains("throttling") || message.contains("rate limit")
                || message.contains("429") || message.contains("额度")) {
            return new ToolError("TOOL_RATE_LIMITED", "retryable",
                    "检索限流：请稍后重试或缩小检索范围");
        }
        if (message.contains("denied") || message.contains("无权") || message.contains("permission")
                || message.contains("403")) {
            return new ToolError("TOOL_PERMISSION_DENIED", "permission",
                    "无权执行该检索：请改用其他参数或换工具");
        }
        if (e instanceof com.travel.common.exception.BusinessException be && be.getCode() >= 40400 && be.getCode() < 40500) {
            return new ToolError("TOOL_NOT_FOUND", "not_found",
                    "目标资源不存在：请确认条件后重试或换工具");
        }
        return new ToolError("TOOL_TRANSIENT_ERROR", "retryable",
                "检索暂时不可用：请稍后重试；持续失败请改用其他工具");
    }
}
