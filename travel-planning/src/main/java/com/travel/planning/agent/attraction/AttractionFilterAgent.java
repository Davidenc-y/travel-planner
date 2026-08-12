package com.travel.planning.agent.attraction;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

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
    private ReactAgent agent;

    public AttractionFilterAgent(@Qualifier("chatModel") ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @PostConstruct
    public void init() throws Exception {
        try {
            this.agent = ReactAgent.builder()
                    .name("attraction_filter")
                    .description("根据用户偏好筛选匹配的景点")
                    .model(chatModel)
                    .systemPrompt("你是景点推荐专家，擅长根据用户偏好筛选最匹配的景点。")
                    .instruction("""
                            基于用户偏好，筛选最匹配的景点。

                            工作流程：
                            1. 分析用户的目的地、兴趣标签、预算、出行人员
                            2. 根据偏好筛选景点（优先匹配兴趣标签，考虑预算限制）
                            3. 每个景点评估：适合度评分(1-5)、推荐时长、预估费用
                            4. 返回 Top 10 景点

                            评分标准：
                            - 5分：完全匹配用户兴趣，预算内
                            - 4分：高度匹配，略微超预算或兴趣部分匹配
                            - 3分：一般匹配

                            必须输出 JSON 数组格式，不要输出其他内容。
                            每个数组元素包含字段：name, type, duration, cost, rating, score, reason
                            示例：name=故宫博物院, type=文化, duration=3-4小时, cost=60, rating=4.8, score=5, reason=完全匹配文化兴趣，预算内
                            """)
                    .outputKey("attractions")
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
}
