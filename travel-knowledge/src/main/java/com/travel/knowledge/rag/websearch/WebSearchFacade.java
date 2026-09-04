package com.travel.knowledge.rag.websearch;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * M9-2：WebSearchPort 统一门面。
 *
 * <p>既有消费方（AttractionEnricher）注入 {@link WebSearchPort} 时命中本
 * {@code @Primary} Bean，内部委托 {@link WebSearchProviderRegistry} 完成
 * 多源选择与 failover；既有 MCP/Noop Adapter 与测试注入点均不动。</p>
 */
@Primary
@Component
@RequiredArgsConstructor
public class WebSearchFacade implements WebSearchPort {

    private final WebSearchProviderRegistry registry;

    @Override
    public Optional<WebSearchResult> search(String query) {
        return registry.search(query);
    }
}
