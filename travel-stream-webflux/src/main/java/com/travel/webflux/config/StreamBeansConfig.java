package com.travel.webflux.config;

import com.travel.common.auth.TokenAuthService;
import com.travel.core.stream.NoopStreamMetrics;
import com.travel.core.stream.StreamMetrics;
import com.travel.planning.service.ChatStreamExecutor;
import com.travel.webflux.pilot.PilotChatStreamExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

/**
 * M6-30：WebFlux 试点显式 Bean。
 *
 * <p>不扫描 com.travel.common，TokenAuthService（M6-25 中立化）与 StreamMetrics
 * 在此装配；配置键与 MVC 应用完全一致（jwt.secret/expiration/refresh-expiration）。</p>
 */
@Configuration
public class StreamBeansConfig {

    @Bean
    public TokenAuthService tokenAuthService(
            @Value("${jwt.secret:travel-planner-secret-key-2026-must-be-long-enough-32chars}")
            String secret,
            @Value("${jwt.expiration:86400000}") long expiration,
            @Value("${jwt.refresh-expiration:604800000}") long refreshExpiration) {
        return new TokenAuthService(secret, expiration, refreshExpiration);
    }

    /**
     * M7-8：Noop 指标为默认兜底——真实 StreamMetrics 实现（如 planning 的
     * MicrometerStreamMetrics @Component）存在时自动让位，避免 IDEA 静态分析
     * 报告“more than one bean of StreamMetrics”歧义（两应用运行时各自只有一份）。
     */
    @Bean
    @ConditionalOnMissingBean(StreamMetrics.class)
    public StreamMetrics streamMetrics() {
        return NoopStreamMetrics.INSTANCE;
    }

    /**
     * 演示执行器（条件 Bean）：真实域下沉提供 ChatStreamExecutor 后自动让位。
     *
     * <p>注意：不使用 @Component+@ConditionalOnMissingBean（扫描阶段会先注册自身，
     * 条件误判“已存在”而跳过，实测 2026-08-25）；用 @Bean 方法保证条件先于注册评估。</p>
     */
    @Bean
    @ConditionalOnMissingBean(ChatStreamExecutor.class)
    public ChatStreamExecutor pilotChatStreamExecutor() {
        return new PilotChatStreamExecutor();
    }

    /**
     * M6-35：WebFlux 下 Feign 解码必需 Bean。
     *
     * <p>Spring Cloud OpenFeign 的 SpringEncoder/SpringDecoder 强制
     * {@code @Autowired HttpMessageConverters}；而 Spring Boot 的
     * HttpMessageConvertersAutoConfiguration 仅对 Servlet 应用生效，
     * WebFlux 应用不会自动创建（实测 2026-08-25：KnowledgeClient 请求可达
     * knowledge 8082 但响应解码失败，RAG/会话知识检索全部降级为空）。</p>
     */
    @Bean
    public HttpMessageConverters feignHttpMessageConverters(ObjectMapper objectMapper) {
        return new HttpMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper));
    }
}
