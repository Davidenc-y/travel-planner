package com.travel.knowledge.rag.websearch;

import java.util.Optional;

/**
 * M8-4：联网搜索端口（Port-Adapter，外部搜索源可替换）。
 *
 * <p>实现：{@link McpWebSearchAdapter}（MCP client，enabled=true 时装配）/
 * {@link NoopWebSearchAdapter}（enabled=false 时兜底直通空）。失败/超时/限频一律
 * 返回 {@link Optional#empty()}，调用方静默降级——不阻塞检索主流程。</p>
 */
public interface WebSearchPort {

    /** 单页搜索；失败/超时/限频返回 empty（调用方静默降级） */
    Optional<WebSearchResult> search(String query);

    /** 搜索结果（结构化抽取输入；原文不进 prompt） */
    record WebSearchResult(String title, String snippet, String url, String retrievedAt) {
    }
}
