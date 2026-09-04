package com.travel.aigateway.config;

import com.travel.aigateway.core.ChatModelFactory;
import com.travel.aigateway.core.ModelProperties;
import com.travel.aigateway.core.ModelProviderType;
import com.travel.aigateway.core.ModelRegistry;
import com.travel.aigateway.route.RoleRoutingChatModel;
import com.travel.aigateway.route.ModelCircuitGuard;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.env.Environment;

/**
 * M7：模型网关自动装配。
 *
 * <p>条件：{@code travel.ai.model-registry.enabled=true} 才装配；false/缺失时不产生任何
 * 网关 Bean，消费方（Batch 2 删除 AiModelConfig 前）保持旧三 Bean 行为——回滚开关。
 * Bean 语义与旧 AiModelConfig 完全同名同构：{@code chatModel}(@Primary) 与
 * {@code lightModel}，全部 @Qualifier 注入点零改动；contentModel 因零消费者不再注册。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "travel.ai.model-registry", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ModelProperties.class)
public class GatewayAutoConfig {

    @Bean
    public ModelRegistry modelRegistry(ModelProperties properties, Environment environment) {
        return new ModelRegistry(properties, environment);
    }

    @Bean
    public ChatModelFactory chatModelFactory(ModelProperties properties, Environment environment) {
        return new ChatModelFactory(properties, environment);
    }

    /** M10-2c：模型维度熔断 Guard（所有角色路由共享同一个 Registry）。 */
    @Bean
    public ModelCircuitGuard modelCircuitGuard(
            @Value("${travel.ai.model-circuit.enabled:true}") boolean enabled,
            @Value("${travel.ai.model-circuit.failure-threshold:5}") int failureThreshold,
            @Value("${travel.ai.model-circuit.open-ms:60000}") long openMs) {
        return new ModelCircuitGuard(enabled, failureThreshold, openMs);
    }

    @Bean
    @Primary
    public ChatModel chatModel(ModelRegistry registry, ChatModelFactory factory,
                               ModelCircuitGuard modelCircuitGuard) {
        return new RoleRoutingChatModel("main", registry, factory, modelCircuitGuard);
    }

    @Bean("lightModel")
    public ChatModel lightModel(ModelRegistry registry, ChatModelFactory factory,
                                ModelCircuitGuard modelCircuitGuard) {
        return new RoleRoutingChatModel("light", registry, factory, modelCircuitGuard);
    }

    /** yml provider 值（dashscope/dashscope-native/openai/openai-compatible）→ 枚举绑定。 */
    @Bean
    @ConfigurationPropertiesBinding
    public Converter<String, ModelProviderType> modelProviderTypeConverter() {
        return ModelProviderType::parse;
    }
}
