package com.travel.knowledge.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Embedding 模型配置
 *
 * <p>直接复用 interview-memory EmbeddingConfig，仅改包名。</p>
 *
 * <p>使用 DashScope text-embedding-v2，用于景点向量化写入 Milvus。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Configuration
public class EmbeddingConfig {

    @Value("${spring.ai.dashscope.api-key:${DASHSCOPE_API_KEY:}}")
    private String apiKey;

    @Bean
    public EmbeddingModel embeddingModel() {
        DashScopeApi api = DashScopeApi.builder().apiKey(apiKey).build();
        return new DashScopeEmbeddingModel(api);
    }
}
