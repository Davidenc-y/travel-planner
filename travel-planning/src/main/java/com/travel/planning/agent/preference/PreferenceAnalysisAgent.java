package com.travel.planning.agent.preference;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.travel.planning.agent.supervisor.TokenUsageInterceptor;
import com.travel.planning.memory.longterm.ProfileToolProvider;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 偏好分析 Agent
 *
 * <p>从用户自然语言输入中提取结构化偏好数据：</p>
 * <ul>
 *   <li>destination: 目的地</li>
 *   <li>days: 出行天数</li>
 *   <li>budget: 预算范围</li>
 *   <li>interests: 兴趣标签</li>
 *   <li>party: 出行人员</li>
 *   <li>travelStyle: 出行风格</li>
 * </ul>
 *
 * <p>使用 qwen-turbo 轻量模型，快速完成分类提取任务。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Component
public class PreferenceAnalysisAgent {

    private final ChatModel lightModel;
    private final TokenUsageInterceptor tokenUsageInterceptor;
    private final ProfileToolProvider profileToolProvider;
    private ReactAgent agent;

    public PreferenceAnalysisAgent(@Qualifier("lightModel") ChatModel lightModel,
                                   TokenUsageInterceptor tokenUsageInterceptor,
                                   ProfileToolProvider profileToolProvider) {
        this.lightModel = lightModel;
        this.tokenUsageInterceptor = tokenUsageInterceptor;
        this.profileToolProvider = profileToolProvider;
    }

    @PostConstruct
    public void init() throws Exception {
        try {
            this.agent = ReactAgent.builder()
                    .name("preference_analysis")
                    .model(lightModel)
                    .description("从用户输入中提取目的地、天数、预算、兴趣等结构化偏好数据")
                    .systemPrompt("你是旅游偏好分析专家，擅长从用户自然语言输入中提取结构化的旅游偏好数据。")
                    .instruction("""
                            从用户输入中提取以下信息：

                            1. destination: 目的地（城市/地区名称）
                            2. days: 出行天数（整数）
                            3. budget: 预算范围（数字，单位：元，如不确定填 null）
                            4. interests: 兴趣标签数组（从以下选择：文化/自然/美食/购物/亲子/休闲）
                            5. party: 出行人员（独行/情侣/家庭/朋友，如不确定填 null）
                            6. travelStyle: 出行风格（ECONOMY/COMFORT/LUXURY，如不确定填 COMFORT）
                            7. specialNeeds: 特殊需求数组（如免门票/无障碍/宠物友好，无则空数组）
                            8. 可调用 get_user_profile 获取当前用户画像（常去目的地/兴趣/预算/风格/历史行程），作为抽取的辅助依据
                            9. 当用户明确表达新的偏好（如"记住/我喜欢/设为/改为"）时，必须先调用 save_user_profile 保存后再输出 JSON，未提及字段保持 null

                            必须输出 JSON 格式，不要输出其他内容。
                            输出示例（请替换为实际值）：
                            destination=北京, days=3, budget=5000, interests=文化+美食, party=家庭, travelStyle=COMFORT, specialNeeds=空
                    """)
                    .outputKey("preference")
                    // F64/B2：画像 Tool 化，Agent 可主动读写长期画像
                    .tools(profileToolProvider.toolCallbacks())
                    // F27：注册 token 用量采集拦截器
                    .interceptors(tokenUsageInterceptor)
                    .build();
            log.info("PreferenceAnalysisAgent 初始化完成");
        } catch (Exception e) {
            log.error("PreferenceAnalysisAgent 初始化失败", e);
            throw new RuntimeException("Failed to build PreferenceAnalysisAgent: " + e.getMessage(), e);
        }
    }

    /**
     * 获取 ReactAgent 实例（供 StateGraph 节点调用）
     */
    public ReactAgent getAgent() {
        return agent;
    }
}
