package com.travel.planning.agent.route;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 路线编排 Agent
 *
 * <p>将筛选出的景点编排为每日行程路线，考虑：</p>
 * <ul>
 *   <li>景点地理位置（避免来回奔波）</li>
 *   <li>开放时间（上午/下午/全天）</li>
 *   <li>游玩时长（避免一天安排过满）</li>
 *   <li>交通方式（步行/公交/打车）</li>
 *   <li>用餐安排（中午就近就餐）</li>
 * </ul>
 *
 * <p>使用 qwen-max 主推理模型，确保路线规划合理性。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Component
public class RouteArrangementAgent {

    private final ChatModel chatModel;
    private ReactAgent agent;

    public RouteArrangementAgent(@Qualifier("chatModel") ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @PostConstruct
    public void init() throws Exception {
        try {
            this.agent = ReactAgent.builder()
                    .name("route_arrangement")
                    .model(chatModel)
                    .instruction("""
                            你是旅游路线编排专家。将筛选出的景点编排为每日行程路线。

                            编排原则：
                            1. 地理就近：同一区域的景点安排在同一天
                            2. 时间合理：考虑景点开放时间和游玩时长
                            3. 节奏适中：每天 3-4 个景点，避免过于紧凑
                            4. 用餐安排：中午就近安排用餐
                            5. 交通方式：景点间推荐合适交通方式

                            每日安排结构：
                            - 上午：1-2 个景点
                            - 中午：用餐
                            - 下午：1-2 个景点
                            - 晚上：自由活动或夜景推荐

                            必须输出 JSON 格式，不要输出其他内容：
                            {
                              "days": [
                                {
                                  "day": 1,
                                  "date": "2026-08-01",
                                  "summary": "故宫-天坛-前门大街",
                                  "attractions": [
                                    {"name":"故宫博物院","timeSlot":"09:00-12:00","cost":60,"notes":"建议提前预约"},
                                    {"name":"天坛公园","timeSlot":"14:00-16:00","cost":15,"notes":"从故宫步行可达"}
                                  ],
                                  "transportMode": "步行+地铁",
                                  "hotelSuggestion": "建议住在王府井附近"
                                }
                              ]
                            }
                            """)
                    .build();
            log.info("RouteArrangementAgent 初始化完成");
        } catch (Exception e) {
            log.error("RouteArrangementAgent 初始化失败", e);
            throw new RuntimeException("Failed to build RouteArrangementAgent: " + e.getMessage(), e);
        }
    }

    /**
     * 获取 ReactAgent 实例（供 StateGraph 节点调用）
     */
    public ReactAgent getAgent() {
        return agent;
    }
}
