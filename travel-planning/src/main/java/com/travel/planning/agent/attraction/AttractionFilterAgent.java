package com.travel.planning.agent.attraction;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.travel.common.util.JsonUtils;
import com.travel.planning.client.KnowledgeClient;
import com.travel.planning.agent.supervisor.TokenUsageInterceptor;
import com.travel.planning.memory.longterm.ProfileToolProvider;
import jakarta.annotation.PostConstruct;
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
 * 景点筛选 Agent
 *
 * <p>基于用户偏好筛选匹配景点，返回 Top 10 候选景点列表。</p>
 *
 * <p>工作流程：</p>
 * <ol>
 *   <li>接收偏好分析结果（目的地、兴趣、预算等）</li>
 *   <li>调用 RAG 检索工具从知识库检索候选景点</li>
 *   <li>根据用户兴趣、预算、出行人员筛选</li>
 *   <li>每个景点评估：适合度评分、推荐时长、预估费用</li>
 *   <li>返回 Top 10 景点</li>
 * </ol>
 *
 * <p>使用 qwen-max 主推理模型，确保筛选质量。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Component
public class AttractionFilterAgent {

    private final ChatModel chatModel;
    private final TokenUsageInterceptor tokenUsageInterceptor;
    private final KnowledgeClient knowledgeClient;
    private final ProfileToolProvider profileToolProvider;
    private ReactAgent agent;

    public AttractionFilterAgent(@Qualifier("chatModel") ChatModel chatModel,
                                 TokenUsageInterceptor tokenUsageInterceptor,
                                 KnowledgeClient knowledgeClient,
                                 ProfileToolProvider profileToolProvider) {
        this.chatModel = chatModel;
        this.tokenUsageInterceptor = tokenUsageInterceptor;
        this.knowledgeClient = knowledgeClient;
        this.profileToolProvider = profileToolProvider;
    }

    @PostConstruct
    public void init() throws Exception {
        try {
            // F50/Phase A（K8）：注册 RAG 检索工具，Agent 可调用知识库获取真实候选景点；
            // knowledge 不可用时工具降级返回空数组，行程仍可生成。
            ToolCallback attractionSearchTool = FunctionToolCallback.builder(
                            "attraction_search",
                            (AttractionSearchRequest req, ToolContext ctx) -> searchAttractions(req))
                    .description("从旅游知识库检索真实景点（含描述/门票/评分/标签），用于筛选候选景点；参数 query 为检索词，topK 为返回数量")
                    .inputType(AttractionSearchRequest.class)
                    .build();

            // F64/B2：画像 Tool 化——attraction_filter 也可读取当前用户画像辅助筛选
            List<ToolCallback> tools = new ArrayList<>();
            tools.add(attractionSearchTool);
            tools.addAll(profileToolProvider.toolCallbacks());

            this.agent = ReactAgent.builder()
                    .name("attraction_filter")
                    .description("根据用户偏好筛选匹配的景点")
                    .model(chatModel)
                    .systemPrompt("你是景点推荐专家，擅长根据用户偏好筛选最匹配的景点。")
                    .instruction("""
                            基于用户偏好，筛选最匹配的景点。

                            工作流程：
                            1. 分析用户的目的地、兴趣标签、预算、出行人员
                            2. 优先使用对话中【知识库检索候选景点】的真实数据筛选；
                               也可调用 attraction_search 工具补充检索（工具返回空时基于自身知识兜底）
                            3. 根据偏好筛选景点（优先匹配兴趣标签，考虑预算限制）
                            4. 每个景点评估：适合度评分(1-5)、推荐时长、预估费用
                            5. 返回 Top 10 景点
                            6. 可调用 get_user_profile 获取当前用户画像（目的地/兴趣/预算/风格），作为筛选的辅助依据

                            评分标准：
                            - 5分：完全匹配用户兴趣，预算内
                            - 4分：高度匹配，略微超预算或兴趣部分匹配
                            - 3分：一般匹配

                            必须输出 JSON 数组格式，不要输出其他内容。
                            每个数组元素包含字段：name, type, duration, cost, rating, score, reason
                            示例：name=故宫博物院, type=文化, duration=3-4小时, cost=60, rating=4.8, score=5, reason=完全匹配文化兴趣，预算内
                    """)
                    .outputKey("attractions")
                    .tools(tools)
                    // F27：注册 token 用量采集拦截器
                    .interceptors(tokenUsageInterceptor)
                    .build();
            log.info("AttractionFilterAgent 初始化完成");
        } catch (Exception e) {
            log.error("AttractionFilterAgent 初始化失败", e);
            throw new RuntimeException("Failed to build AttractionFilterAgent: " + e.getMessage(), e);
        }
    }

    /**
     * 获取 ReactAgent 实例（供 StateGraph 节点调用）
     */
    public ReactAgent getAgent() {
        return agent;
    }

    /**
     * 调用知识库检索；失败降级返回空数组（不阻断行程生成）
     */
    private String searchAttractions(AttractionSearchRequest req) {
        try {
            var resp = knowledgeClient.search("hybrid", req.query(), req.topK() > 0 ? req.topK() : 10);
            if (resp == null || resp.getData() == null) {
                log.warn("[AttractionFilterAgent] 知识库检索返回空: query={}", req.query());
                return "[]";
            }
            // F61：工具调用可观测——成功也打印，便于确认 Agent 是否真的走了知识库。
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
