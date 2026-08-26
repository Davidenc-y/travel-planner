package com.travel.planning.memory.knowledge;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * M4-2/P0-2：planning 侧检索注入 topK 配置化（替换 ChatBudgetStep 硬编码）。
 *
 * <p>对应 yml：{@code travel.rag.session-context.top-k} /
 * {@code travel.rag.attraction-candidates.top-k}。默认值等于 F63/F83 硬编码值，
 * 行为不变；评测脚本/Rerank 依赖可调 topK。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.rag")
public class RagInjectionProperties {

    /** F83：会话知识检索注入 topK（默认 8，避免类型加分把行程切片挤出注入） */
    private int sessionContextTopK = 8;

    /** F63：知识库候选景点注入 topK（默认 5；下游另有 min(topK,10) 夹逼） */
    private int attractionCandidatesTopK = 5;

    /**
     * M4-5b：会话知识二次取父开关（{@code travel.rag.parent-context.enabled}，默认 true）。
     * 开启时 itinerary_day 命中会按 seq 前缀取回该行程全部天块替换命中子块（完整父视图）；
     * 取父失败降级保留原命中，行为与关闭时一致（回归零风险）。
     */
    private boolean parentContextEnabled = true;
}
