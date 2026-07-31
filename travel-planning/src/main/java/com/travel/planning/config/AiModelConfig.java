package com.travel.planning.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * AI 多模型路由配置
 *
 * <p>配置三个 ChatModel Bean，分别对应不同场景：</p>
 * <ul>
 *   <li>chatModel (qwen-max): 主推理，行程编排、路线规划</li>
 *   <li>lightModel (qwen-turbo): 轻量分类，偏好分析、意图识别</li>
 *   <li>contentModel (qwen-plus): 内容生成，景点描述、文案润色</li>
 * </ul>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Configuration
public class AiModelConfig {

    @Value("${spring.ai.dashscope.api-key:${DASHSCOPE_API_KEY:}}")
    private String apiKey;

    /**
     * 主推理模型：qwen-max
     * <p>用于 SupervisorAgent 编排、景点筛选、路线编排等复杂推理场景。</p>
     */
    @Bean
    @Primary
    public ChatModel chatModel() {
        return buildModel("qwen-max", 0.7, 4000);
    }

    /**
     * 轻量模型：qwen-turbo
     * <p>用于偏好分析、意图识别、预算估算等轻量分类场景，速度快成本低。</p>
     */
    @Bean("lightModel")
    public ChatModel lightModel() {
        return buildModel("qwen-turbo", 0.5, 2000);
    }

    /**
     * 内容生成模型：qwen-plus
     * <p>用于景点描述生成、行程文案润色等高质量内容生成场景。</p>
     */
    @Bean("contentModel")
    public ChatModel contentModel() {
        return buildModel("qwen-plus", 0.3, 4000);
    }

    /**
     * 构建 DashScope ChatModel
     *
     * @param modelName 模型名称
     * @param temperature 温度参数（0-1，越低越确定）
     * @param maxToken 最大生成 token 数
     */
    private ChatModel buildModel(String modelName, double temperature, int maxToken) {
        DashScopeApi api = DashScopeApi.builder().apiKey(apiKey).build();
        return DashScopeChatModel.builder()
                .dashScopeApi(api)
                .defaultOptions(DashScopeChatOptions.builder()
                        .model(modelName)
                        .temperature(temperature)
                        .maxToken(maxToken)
                        .topP(0.9)
                        .build())
                .build();
    }
}
