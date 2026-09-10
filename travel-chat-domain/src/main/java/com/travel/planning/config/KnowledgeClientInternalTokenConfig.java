package com.travel.planning.config;

import com.travel.common.config.GrayFlags;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M21-3（SEC-02-04 联动）：knowledge 侧管理面端点（/api/v1/memory/** 等）启用
 * 共享密钥拦截后，{@code KnowledgeClient}（Feign）调用必须携带
 * {@code X-Internal-Token}（与 knowledge 侧 {@code travel.internal.token} 同源，
 * 受控配置均为 ${TRAVEL_INTERNAL_TOKEN:} 空缺省、local yml 同值覆盖）。
 *
 * <p>未配置时不注入（调用将被 knowledge fail-closed 拒绝并降级——与既有
 * "knowledge 停机→WARN 降级空注入"语义一致，不阻断对话主链路）。</p>
 */
@Configuration
public class KnowledgeClientInternalTokenConfig {

    @Bean
    public RequestInterceptor internalTokenInterceptor(
            @Value("${travel.internal.token:}") String internalToken) {
        return template -> {
            if (internalToken != null && !internalToken.isBlank()
                    && template.url() != null
                    && (template.url().contains("/api/v1/memory/")
                        || template.url().contains("/api/v1/rag/"))) {
                template.header(GrayFlags.HEADER_INTERNAL_TOKEN, internalToken);
            }
        };
    }
}
