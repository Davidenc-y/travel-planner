package com.travel.planning.client;

import feign.Request;
import feign.Retryer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.TimeUnit;

/**
 * KnowledgeClient Feign 专属配置（B1.1：传输收敛与弹性化批次）
 *
 * <p>经 {@link KnowledgeClient} 的 @FeignClient(configuration=...) 绑定，仅作用于该 client；
 * 刻意不标注 @Configuration，避免被组件扫描提升为全局 Feign 默认配置。</p>
 *
 * <p>超时键带默认值，可经环境变量覆盖：KNOWLEDGE_FEIGN_CONNECT_TIMEOUT（默认 2000ms）、
 * KNOWLEDGE_FEIGN_READ_TIMEOUT（默认 8000ms）。重试策略 Retryer.Default(0, MAX, 2)（NEVER_BACKOFF
 * 等价：失败后不退避立即重试，maxAttempts=2 恰好重试 1 次）。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
public class KnowledgeClientConfig {

    @Bean
    public Request.Options knowledgeFeignOptions(
            @Value("${KNOWLEDGE_FEIGN_CONNECT_TIMEOUT:2000}") long connectTimeoutMs,
            @Value("${KNOWLEDGE_FEIGN_READ_TIMEOUT:8000}") long readTimeoutMs) {
        return new Request.Options(connectTimeoutMs, TimeUnit.MILLISECONDS,
                readTimeoutMs, TimeUnit.MILLISECONDS, true);
    }

    @Bean
    public Retryer knowledgeFeignRetryer() {
        return new Retryer.Default(0L, Long.MAX_VALUE, 2);
    }
}
