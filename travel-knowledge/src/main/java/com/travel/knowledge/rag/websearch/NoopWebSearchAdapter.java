package com.travel.knowledge.rag.websearch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * M8-4：联网搜索 noop 兜底——web-search.enabled=false 时直通空
 * （行为等价 Phase 1/2/3，零外部依赖、零延迟）。
 */
@Slf4j
@Component
@ConditionalOnMissingBean(WebSearchPort.class)
public class NoopWebSearchAdapter implements WebSearchPort {

    @Override
    public Optional<WebSearchResult> search(String query) {
        log.debug("[WebSearch] noop 直通（web-search.enabled=false）: query={}", query);
        return Optional.empty();
    }
}
