package com.travel.planning.agent.preference;

import com.travel.planning.agent.AbstractReactSubAgent;
import com.travel.planning.agent.supervisor.TokenUsageInterceptor;
import com.travel.planning.memory.longterm.ProfileToolProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 偏好分析 Agent（M3-7：基于 AbstractReactSubAgent 模板，行为与 F64/F27 原实现一致）。
 */
@Slf4j
@Component
public class PreferenceAnalysisAgent extends AbstractReactSubAgent {

    private final ChatModel lightModel;
    private final TokenUsageInterceptor tokenUsageInterceptor;
    private final ProfileToolProvider profileToolProvider;

    public PreferenceAnalysisAgent(@Qualifier("lightModel") ChatModel lightModel,
                                   TokenUsageInterceptor tokenUsageInterceptor,
                                   ProfileToolProvider profileToolProvider) {
        this.lightModel = lightModel;
        this.tokenUsageInterceptor = tokenUsageInterceptor;
        this.profileToolProvider = profileToolProvider;
    }

    @Override
    protected ChatModel model() {
        return lightModel;
    }

    @Override
    protected String name() {
        return "preference_analysis";
    }

    @Override
    protected String description() {
        return "从用户输入中提取目的地、天数、预算、兴趣等结构化偏好数据";
    }

    @Override
    protected String systemPrompt() {
        return "你是旅游偏好分析专家，擅长从用户自然语言输入中提取结构化的旅游偏好数据。";
    }

    @Override
    protected String instruction() {
        return """
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
        """;
    }

    @Override
    protected String outputKey() {
        return "preference";
    }

    @Override
    protected List<ToolCallback> tools() {
        return profileToolProvider.toolCallbacks();
    }

    @Override
    protected TokenUsageInterceptor interceptor() {
        return tokenUsageInterceptor;
    }
}
