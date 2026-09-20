package com.travel.planning.memory.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RK-1：景点候选注入策略。low-confidence-policy=filter（默认）丢弃 RerankGate
 * 标记的低置信候选并在全滤时注入拒答指引；keep 回退为旧全量注入行为。
 *
 * <p>注册方式对照同包 RagInjectionProperties 既有先例（@Component + @ConfigurationProperties，
 * A-1 自审裁决 R4：03 骨架未含注册机制，按本仓实际装配先例适配，否则构造注入缺 bean）。</p>
 */
@Component
@ConfigurationProperties(prefix = "travel.rag.attraction-candidates")
public class RagInjectionPolicy {

    /** filter | keep */
    private String lowConfidencePolicy = "filter";

    public boolean isFilterEnabled() {
        return "filter".equalsIgnoreCase(lowConfidencePolicy);
    }

    public String getLowConfidencePolicy() { return lowConfidencePolicy; }
    public void setLowConfidencePolicy(String v) { this.lowConfidencePolicy = v; }
}
