package com.travel.aigateway.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * M7 Batch 5（T28）：注册表关闭时的回滚兜底配置。
 *
 * <p>{@code travel.ai.model-registry.enabled=false}（或缺失）时，本配置提供与旧
 * AiModelConfig 同名的 {@code chatModel}(@Primary) / {@code lightModel} Bean
 * （DashScope 直连，模型名走旧键 {@code travel.ai.models.main/light}，默认
 * qwen-max/qwen-turbo），保证 Batch 2 删除 AiModelConfig 后回滚开关仍然成立。
 * contentModel 因零消费者不再提供。api-key 读取顺序兼容 local 配置：
 * {@code spring.ai.dashscope.api-key} → {@code DASHSCOPE_API_KEY}。</p>
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "travel.ai.model-registry", name = "enabled",
        havingValue = "false", matchIfMissing = true)
public class LegacyModelFallbackConfig {

    @Value("${spring.ai.dashscope.api-key:${DASHSCOPE_API_KEY:}}")
    private String apiKey;

    @Value("${travel.ai.models.main:qwen-max}")
    private String mainModel;

    @Value("${travel.ai.models.light:qwen-turbo}")
    private String lightModel;

    /** 单次 LLM 调用超时（秒），沿用旧 F24 硬性退出边界。 */
    @Value("${travel.ai.timeout.seconds:120}")
    private long llmTimeoutSeconds;

    @Bean
    @Primary
    public ChatModel chatModel() {
        return buildModel(mainModel, 0.7, 4000);
    }

    @Bean("lightModel")
    public ChatModel lightModel() {
        return buildModel(lightModel, 0.5, 2000);
    }

    private ChatModel buildModel(String modelName, double temperature, int maxToken) {
        DashScopeApi api = DashScopeApi.builder()
                .apiKey(apiKey)
                .restClientBuilder(buildRestClientBuilder())
                .webClientBuilder(buildWebClientBuilder())
                .build();
        log.warn("[ModelFallback] 模型注册表未启用，使用旧配置直连 DashScope: main={}, light={}",
                mainModel, lightModel);
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

    private RestClient.Builder buildRestClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(llmTimeoutSeconds).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(llmTimeoutSeconds).toMillis());
        return RestClient.builder().requestFactory(factory);
    }

    private WebClient.Builder buildWebClientBuilder() {
        java.net.http.HttpClient jdkClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(llmTimeoutSeconds))
                .build();
        return WebClient.builder().clientConnector(new JdkClientHttpConnector(jdkClient));
    }
}
