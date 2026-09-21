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
 * KNOWLEDGE_FEIGN_READ_TIMEOUT（默认 12000ms）。S-A2/A-4（L4 Feign 纪律）：重试禁用
 * Retryer.Default(0, MAX, 1)（maxAttempts=1=重试 0 次——rag 检索读语义重试无益且双倍烧
 * knowledge 45s 管线，20260920 三进程日志 P1-③ F-1 放大实证）；读超时 8000→12000ms
 * 与复杂查询 ≤8s 端到端目标的 llm 层预算对齐。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
public class KnowledgeClientConfig {

    @Bean
    public Request.Options knowledgeFeignOptions(
            @Value("${KNOWLEDGE_FEIGN_CONNECT_TIMEOUT:2000}") long connectTimeoutMs,
            @Value("${KNOWLEDGE_FEIGN_READ_TIMEOUT:12000}") long readTimeoutMs) {
        return new Request.Options(connectTimeoutMs, TimeUnit.MILLISECONDS,
                readTimeoutMs, TimeUnit.MILLISECONDS, true);
    }

    @Bean
    public Retryer knowledgeFeignRetryer() {
        return new Retryer.Default(0L, Long.MAX_VALUE, 1);
    }
}
