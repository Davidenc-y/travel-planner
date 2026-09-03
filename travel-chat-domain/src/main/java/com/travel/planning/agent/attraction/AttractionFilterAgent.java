package com.travel.planning.agent.attraction;

import com.travel.common.util.JsonUtils;
import com.travel.planning.agent.AbstractReactSubAgent;
import com.travel.planning.agent.supervisor.TokenUsageInterceptor;
import com.travel.planning.agent.supervisor.ModelRouteInterceptor;
import com.travel.planning.agent.supervisor.QuotaShortCircuitInterceptor;
import com.travel.planning.client.KnowledgeClient;
import com.travel.planning.memory.longterm.ProfileToolProvider;
import com.travel.planning.prompt.PromptTemplates;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 景点筛选 Agent（M3-7：基于 AbstractReactSubAgent 模板，行为与 F50/F64/F27 原实现一致）。
 */
@Slf4j
@Component
public class AttractionFilterAgent extends AbstractReactSubAgent {

    private final ChatModel chatModel;
    private final TokenUsageInterceptor tokenUsageInterceptor;
    private final ModelRouteInterceptor modelRouteInterceptor;
    private final QuotaShortCircuitInterceptor quotaShortCircuitInterceptor;
    private final KnowledgeClient knowledgeClient;
    private final ProfileToolProvider profileToolProvider;
    private final PromptTemplates promptTemplates;

    public AttractionFilterAgent(@Qualifier("chatModel") ChatModel chatModel,
                                 TokenUsageInterceptor tokenUsageInterceptor,
                                 ModelRouteInterceptor modelRouteInterceptor,
                                 QuotaShortCircuitInterceptor quotaShortCircuitInterceptor,
                                 KnowledgeClient knowledgeClient,
                                 ProfileToolProvider profileToolProvider,
                                 PromptTemplates promptTemplates) {
        this.chatModel = chatModel;
        this.tokenUsageInterceptor = tokenUsageInterceptor;
        this.modelRouteInterceptor = modelRouteInterceptor;
        this.quotaShortCircuitInterceptor = quotaShortCircuitInterceptor;
        this.knowledgeClient = knowledgeClient;
        this.profileToolProvider = profileToolProvider;
        this.promptTemplates = promptTemplates;
    }

    @Override
    protected ChatModel model() {
        return chatModel;
    }

    @Override
    protected String name() {
        return "attraction_filter";
    }

    @Override
    protected String description() {
        return "根据用户偏好筛选匹配的景点";
    }

    @Override
    protected String systemPrompt() {
        return promptTemplates.agentAttractionSystem();
    }

    @Override
    protected String instruction() {
        return promptTemplates.agentAttractionInstruction();
    }

    @Override
    protected String outputKey() {
        return "attractions";
    }

    @Override
    protected List<ToolCallback> tools() {
        ToolCallback attractionSearchTool = FunctionToolCallback.builder(
                        "attraction_search",
                        (AttractionSearchRequest req, ToolContext ctx) -> searchAttractions(req))
                .description("从旅游知识库检索真实景点（含描述/门票/评分/标签），用于筛选候选景点；参数 query 为检索词，topK 为返回数量")
                .inputType(AttractionSearchRequest.class)
                .build();
        List<ToolCallback> tools = new ArrayList<>();
        tools.add(attractionSearchTool);
        tools.addAll(profileToolProvider.toolCallbacks());
        return tools;
    }

    @Override
    protected TokenUsageInterceptor interceptor() {
        return tokenUsageInterceptor;
    }

    @Override
    protected ModelRouteInterceptor modelRouteInterceptor() {
        return modelRouteInterceptor;
    }

    @Override
    protected QuotaShortCircuitInterceptor quotaShortCircuitInterceptor() {
        return quotaShortCircuitInterceptor;
    }

    /** 调用知识库检索；失败降级返回空数组（不阻断行程生成） */
    private String searchAttractions(AttractionSearchRequest req) {
        try {
            var resp = knowledgeClient.search("hybrid", req.query(), req.topK() > 0 ? req.topK() : 10);
            if (resp == null || resp.getData() == null) {
                log.warn("[AttractionFilterAgent] 知识库检索返回空: query={}", req.query());
                return "[]";
            }
            log.info("[AttractionFilterAgent] attraction_search 调用成功: query={}, topK={}, 结果 {} 条",
                    req.query(), req.topK(), resp.getData().size());
            return JsonUtils.toJson(resp.getData());
        } catch (Exception e) {
            log.warn("[AttractionFilterAgent] 知识库检索失败，降级空结果: {}", e.getMessage());
            return "[]";
        }
    }

    /** attraction_search 工具入参 */
    public record AttractionSearchRequest(String query, int topK) {
    }
}
