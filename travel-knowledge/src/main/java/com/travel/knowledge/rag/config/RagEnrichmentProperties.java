package com.travel.knowledge.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * M8-1：检索结果结构化补全配置。
 *
 * <p>对应 yml：{@code travel.rag.enrichment.*}。
 * {@code enabled=false} 时 {@code AttractionEnricher} 直通原结果，
 * 行为回到 M8-1 之前（回滚开关）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.rag.enrichment")
public class RagEnrichmentProperties {

    /** 检索结果结构化补全开关（false 时 enrich 直通） */
    private boolean enabled = true;
}
