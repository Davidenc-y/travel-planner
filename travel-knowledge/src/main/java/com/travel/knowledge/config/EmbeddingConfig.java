package com.travel.knowledge.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.travel.aigateway.core.GatewayException;
import com.travel.aigateway.core.ModelDescriptor;
import com.travel.aigateway.core.ModelRegistry;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Embedding 模型配置
 *
 * <p>使用 DashScope text-embedding-v2，用于景点向量化写入 Milvus。
 * M7 Batch 4：模型名与 api-key 改从模型注册表读取（embedding 描述符，
 * roles=[embedding]、selectable=false），行为不变——SDK 默认模型即
 * text-embedding-v2，注册表强制校验 key 一致；api-key 走 D7 环境变量语义。</p>
 *
 * <p>MM-7：模型键配置化——原硬编码常量改读 {@code travel.ai.embedding.model}
 * （yml 可再经 {@code AI_EMBEDDING_MODEL} 环境变量覆盖），默认值与原常量逐字节
 * 一致，默认零配置行为不变；注册表校验逻辑（存在/enabled/键一致）原样保留。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Configuration
public class EmbeddingConfig {

    /** MM-7：原 EMBEDDING_MODEL_KEY 常量改配置化（默认值逐字节一致）。 */
    @Value("${travel.ai.embedding.model:text-embedding-v2}")
    private String embeddingModelKey;

    private final ModelRegistry modelRegistry;
    private final Environment environment;

    public EmbeddingConfig(ModelRegistry modelRegistry, Environment environment) {
        this.modelRegistry = modelRegistry;
        this.environment = environment;
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        ModelDescriptor descriptor = modelRegistry.get(embeddingModelKey)
                .orElseThrow(() -> new GatewayException(
                        "Embedding 模型未注册: " + embeddingModelKey));
        if (!descriptor.enabled()) {
            throw new GatewayException("Embedding 模型未启用: " + embeddingModelKey);
        }
        if (!embeddingModelKey.equals(descriptor.key())) {
            throw new GatewayException("Embedding 模型键不一致: " + descriptor.key());
        }
        DashScopeApi api = DashScopeApi.builder().apiKey(resolveKey(descriptor)).build();
        // SDK 默认模型即 text-embedding-v2，与注册表键一致；不强制 options，避免 MetadataMode 行为漂移
        return new DashScopeEmbeddingModel(api);
    }

    private String resolveKey(ModelDescriptor descriptor) {
        String value = environment.getProperty(descriptor.apiKeyEnv());
        if (value == null || value.isBlank()) {
            value = System.getenv(descriptor.apiKeyEnv());
        }
        if (value == null || value.isBlank()) {
            throw new GatewayException("Embedding API Key 缺失: " + descriptor.apiKeyEnv());
        }
        return value;
    }
}
