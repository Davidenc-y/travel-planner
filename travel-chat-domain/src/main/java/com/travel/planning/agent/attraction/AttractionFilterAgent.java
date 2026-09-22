package com.travel.planning.agent.attraction;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import com.travel.common.util.JsonUtils;
import com.travel.planning.agent.AbstractReactSubAgent;
import com.travel.planning.agent.support.DestinationContext;
import com.travel.planning.agent.supervisor.TokenUsageInterceptor;
import com.travel.planning.agent.supervisor.ModelRouteInterceptor;
import com.travel.planning.agent.supervisor.QuotaShortCircuitInterceptor;
import com.travel.planning.client.KnowledgeSearchPort;
import com.travel.planning.memory.longterm.ProfileToolProvider;
import com.travel.memory.prompt.PromptTemplates;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
    private final KnowledgeSearchPort knowledgeClient;
    private final ProfileToolProvider profileToolProvider;
    private final PromptTemplates promptTemplates;

    /** T-1：目的地前缀开关（false=完全跳过前缀逻辑，行为等价现状——一键回退）。包级可见=单测直赋（E-4：不走 ReflectionTestUtils） */
    @Value("${travel.chat.attraction-search.destination-prefix-enabled:true}")
    boolean destinationPrefixEnabled = true;

    public AttractionFilterAgent(@Qualifier("chatModel") ChatModel chatModel,
                                 TokenUsageInterceptor tokenUsageInterceptor,
                                 ModelRouteInterceptor modelRouteInterceptor,
                                 QuotaShortCircuitInterceptor quotaShortCircuitInterceptor,
                                 KnowledgeSearchPort knowledgeClient,
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
                        (AttractionSearchRequest req, ToolContext ctx) -> searchAttractions(req, ctx))
                .description("从旅游知识库检索真实景点（含描述/门票/评分/标签），用于筛选候选景点；参数 query 为检索词，topK 为返回数量；检索词应聚焦当前行程目的地城市（系统亦会自动补目的地前缀）")
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

    /**
     * 调用知识库检索；失败降级返回空数组（不阻断行程生成）。
     * T-1：开关开且锚定目的地非空且 query 未含目的地时，检索词加目的地前缀
     * （确定性硬约束，REFINE 跨城缺陷修复；只加前缀不删词）。
     */
    private String searchAttractions(AttractionSearchRequest req, ToolContext ctx) {
        try {
            String query = req.query();
            String destination = resolveDestination(ctx);
            if (destinationPrefixEnabled && destination != null && !destination.isBlank()
                    && query != null && !query.contains(destination)) {
                query = destination + " " + query;
            }
            var resp = knowledgeClient.search("hybrid", query, req.topK() > 0 ? req.topK() : 10);
            if (resp == null || resp.getData() == null) {
                log.warn("[AttractionFilterAgent] 知识库检索返回空: query={}, destination={}",
                        query, destination);
                return "[]";
            }
            log.info("[AttractionFilterAgent] attraction_search 调用成功: query={}, destination={}, topK={}, 结果 {} 条",
                    query, destination, req.topK(), resp.getData().size());
            return JsonUtils.toJson(resp.getData());
        } catch (Exception e) {
            log.warn("[AttractionFilterAgent] 知识库检索失败，降级空结果: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * T-1：从 ToolContext 读取锚定目的地（复刻 ProfileToolProvider.resolveUserId 先例：
     * ToolContextHelper.getConfig → RunnableConfig.metadata）。不可用返回 null。
     */
    private static String resolveDestination(ToolContext ctx) {
        try {
            if (ctx == null) {
                return null;
            }
            Optional<RunnableConfig> config = ToolContextHelper.getConfig(ctx);
            if (config.isPresent()) {
                Optional<Object> value =
                        config.get().metadata(DestinationContext.DESTINATION_METADATA_KEY);
                return value.map(String::valueOf).orElse(null);
            }
        } catch (Exception e) {
            log.debug("[AttractionFilterAgent] 目的地 metadata 读取失败（按 null 处理）: {}",
                    e.getMessage());
        }
        return null;
    }

    /** attraction_search 工具入参 */
    public record AttractionSearchRequest(String query, int topK) {
    }
}
