package com.travel.planning.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * AI 多模型路由配置
 *
 * <p>配置三个 ChatModel Bean，分别对应不同场景：</p>
 * <ul>
 *   <li>chatModel (travel.ai.models.main): 主推理，行程编排、路线规划</li>
 *   <li>lightModel (travel.ai.models.light): 轻量分类，偏好分析、意图识别</li>
 *   <li>contentModel (travel.ai.models.coder): 内容生成，景点描述、文案润色</li>
 * </ul>
 *
 * <p>模型名统一从 {@code travel.ai.models.*} 读取（application.yml /
 * application-local.yml 均可覆盖），不再硬编码。当前本地/默认配置使用 qwen3.7-max：
 * 旧配置硬编码 qwen-max / qwen-plus 会命中 DashScope 免费额度耗尽（403
 * AllocationQuota.FreeTierOnly），而 qwen3.7-max 实测可用。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Configuration
public class AiModelConfig {

    @Value("${spring.ai.dashscope.api-key:${DASHSCOPE_API_KEY:}}")
    private String apiKey;

    @Value("${travel.ai.models.main:qwen-max}")
    private String mainModel;

    @Value("${travel.ai.models.light:qwen-turbo}")
    private String lightModel;

    @Value("${travel.ai.models.coder:qwen-plus}")
    private String coderModel;

    /**
     * 单次 LLM 调用超时（秒）。作为每节点 LLM 调用层的硬性退出边界：
     * 避免单次 DashScope 请求悬挂时，只能依赖整体工作流 300s 超时兜底。
     * 默认 120s（覆盖正常 30-60s 调用，同时给整体超时留出重试余量）。
     */
    @Value("${travel.ai.timeout.seconds:120}")
    private long llmTimeoutSeconds;

    /**
     * 启动时打印生效的模型路由与掩码后的 API Key，便于核对实际运行配置。
     */
    @PostConstruct
    public void logModelRouting() {
        log.info("AI 模型路由生效: main={}, light={}, coder={}, apiKey={}",
                mainModel, lightModel, coderModel, maskApiKey(apiKey));
    }

    private static String maskApiKey(String key) {
        if (key == null || key.length() < 8) {
            return "***";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    /**
     * 主推理模型：travel.ai.models.main（默认 qwen-max）
     * <p>用于 SupervisorAgent 编排、景点筛选、路线编排等复杂推理场景。</p>
     */
    @Bean
    @Primary
    public ChatModel chatModel() {
        return buildModel(mainModel, 0.7, 4000);
    }

    /**
     * 轻量模型：travel.ai.models.light（默认 qwen-turbo）
     * <p>用于偏好分析、意图识别、预算估算等轻量分类场景，速度快成本低。</p>
     */
    @Bean("lightModel")
    public ChatModel lightModel() {
        return buildModel(lightModel, 0.5, 2000);
    }

    /**
     * 内容生成模型：travel.ai.models.coder（默认 qwen-plus）
     * <p>用于景点描述生成、行程文案润色等高质量内容生成场景。</p>
     */
    @Bean("contentModel")
    public ChatModel contentModel() {
        return buildModel(coderModel, 0.3, 4000);
    }

    /**
     * 构建 DashScope ChatModel
     *
     * @param modelName 模型名称
     * @param temperature 温度参数（0-1，越低越确定）
     * @param maxToken 最大生成 token 数
     */
    private ChatModel buildModel(String modelName, double temperature, int maxToken) {
        DashScopeApi api = DashScopeApi.builder()
                .apiKey(apiKey)
                // 注入带超时的 HTTP 客户端（F24 修复：单次 LLM 调用超时边界）
                .restClientBuilder(buildRestClientBuilder())
                .webClientBuilder(buildWebClientBuilder())
                .build();
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

    /**
     * 构建带连接/读取超时的 RestClient.Builder（DashScope 非流式调用路径）。
     */
    private RestClient.Builder buildRestClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(llmTimeoutSeconds).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(llmTimeoutSeconds).toMillis());
        return RestClient.builder().requestFactory(factory);
    }

    /**
     * 构建带响应超时的 WebClient.Builder（DashScope 流式调用路径）。
     */
    private WebClient.Builder buildWebClientBuilder() {
        java.net.http.HttpClient jdkClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(llmTimeoutSeconds))
                .build();
        return WebClient.builder().clientConnector(
                new org.springframework.http.client.reactive.JdkClientHttpConnector(jdkClient));
    }
}
