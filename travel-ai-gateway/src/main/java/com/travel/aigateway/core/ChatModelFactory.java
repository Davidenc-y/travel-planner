package com.travel.aigateway.core;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * M7：ChatModel 工厂——按描述符构建并缓存（computeIfAbsent 单飞），吸收 Provider 差异。
 *
 * <p>超时注入沿用 AiModelConfig F24 修复模式（RestClient/WebClient 双通道）；
 * per-model timeoutSeconds 优先，否则全局 defaultTimeoutSeconds。
 * key 一律从 apiKeyEnv 指向的环境变量解析（D7），缺失抛 GatewayException。</p>
 */
@Slf4j
public class ChatModelFactory {

    private final ModelProperties properties;
    private final Environment environment;
    private final ConcurrentMap<String, ChatModel> cache = new ConcurrentHashMap<>();

    public ChatModelFactory(ModelProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    public ChatModel obtain(ModelDescriptor descriptor) {
        return cache.computeIfAbsent(descriptor.key(), key -> {
            log.info("[ChatModelFactory] 首次构建模型: key={}, provider={}, baseUrl={}",
                    key, descriptor.provider(), descriptor.baseUrl());
            return create(descriptor);
        });
    }

    private ChatModel create(ModelDescriptor d) {
        int timeout = d.timeoutSeconds() != null
                ? d.timeoutSeconds() : properties.defaultTimeoutSeconds();
        return switch (d.provider()) {
            case DASHSCOPE_NATIVE -> createDashScope(d, timeout);
            case OPENAI_COMPATIBLE -> createOpenAiCompatible(d, timeout);
        };
    }

    private ChatModel createDashScope(ModelDescriptor d, int timeoutSeconds) {
        DashScopeApi api = DashScopeApi.builder()
                .apiKey(resolveKey(d))
                .restClientBuilder(restClientBuilder(timeoutSeconds))
                .webClientBuilder(webClientBuilder(timeoutSeconds))
                .build();
        DashScopeChatOptions.DashScopeChatOptionsBuilder options =
                DashScopeChatOptions.builder().model(d.key());
        if (d.temperature() != null) {
            options.temperature(d.temperature());
        }
        if (d.maxTokens() != null) {
            options.maxToken(d.maxTokens());
        }
        if (d.topP() != null) {
            options.topP(d.topP());
        }
        return DashScopeChatModel.builder()
                .dashScopeApi(api)
                .defaultOptions(options.build())
                .build();
    }

    private ChatModel createOpenAiCompatible(ModelDescriptor d, int timeoutSeconds) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(d.baseUrl())
                .apiKey(resolveKey(d))
                .restClientBuilder(restClientBuilder(timeoutSeconds))
                .webClientBuilder(webClientBuilder(timeoutSeconds))
                .build();
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().model(d.key());
        if (d.temperature() != null) {
            options.temperature(d.temperature());
        }
        if (d.maxTokens() != null) {
            options.maxTokens(d.maxTokens());
        }
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options.build())
                .build();
    }

    private String resolveKey(ModelDescriptor d) {
        if (d.apiKeyEnv() == null || d.apiKeyEnv().isBlank()) {
            throw new GatewayException("模型 " + d.key() + " 未配置 apiKeyEnv");
        }
        String value = environment != null ? environment.getProperty(d.apiKeyEnv()) : null;
        if (value == null || value.isBlank()) {
            value = System.getenv(d.apiKeyEnv());
        }
        if (value == null || value.isBlank()) {
            throw new GatewayException("模型 " + d.key() + " 的 API Key 环境变量缺失: " + d.apiKeyEnv());
        }
        return value;
    }

    private static RestClient.Builder restClientBuilder(int timeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(timeoutSeconds).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(timeoutSeconds).toMillis());
        return RestClient.builder().requestFactory(factory);
    }

    private static WebClient.Builder webClientBuilder(int timeoutSeconds) {
        java.net.http.HttpClient jdkClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
        return WebClient.builder().clientConnector(new JdkClientHttpConnector(jdkClient));
    }
}
