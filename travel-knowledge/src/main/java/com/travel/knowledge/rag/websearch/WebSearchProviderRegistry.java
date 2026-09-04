package com.travel.knowledge.rag.websearch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * M9-2：搜索源注册表（复用 ModelRegistry 范式）。
 *
 * <p>选择：providers 配置按 priority 升序取首个 enabled 源；单源失败自动降级
 * 下一源（failover）；全挂返回 empty（调用方静默降级）。providers 为空时保留
 * M8-4 传统单源语义（总开关控制 duckduckgo/MCP）。</p>
 */
@Slf4j
@Component
public class WebSearchProviderRegistry {

    private final WebSearchProperties properties;
    private final Map<String, WebSearchPort> available = new HashMap<>();

    @Autowired
    public WebSearchProviderRegistry(WebSearchProperties properties,
                                     ObjectProvider<TavilyWebSearchAdapter> tavily,
                                     ObjectProvider<McpWebSearchAdapter> mcp) {
        this.properties = properties;
        register("tavily", tavily.getIfAvailable());
        register("duckduckgo", mcp.getIfAvailable());
    }

    /** 测试注入：直接给可用端口表 */
    WebSearchProviderRegistry(WebSearchProperties properties,
                              Map<String, WebSearchPort> available) {
        this.properties = properties;
        this.available.putAll(available);
    }

    private void register(String name, WebSearchPort port) {
        if (port != null) {
            available.put(name, port);
        }
    }

    /**
     * 按优先级尝试 enabled 搜索源；全部失败返回 empty。
     * 传统模式（providers 空且 duckduckgo 可用）直接委托 MCP。
     */
    public Optional<WebSearchPort.WebSearchResult> search(String query) {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }
        var providers = properties.enabledProviders();
        if (providers.isEmpty() && (properties.getProviders() == null
                || properties.getProviders().isEmpty())) {
            WebSearchPort legacy = available.get("duckduckgo");
            if (legacy != null && properties.isProviderEnabled("duckduckgo")) {
                return legacy.search(query);
            }
            return Optional.empty();
        }
        if (providers.isEmpty()) {
            log.warn("[WebSearch] 总开关开启但无 enabled 搜索源，返回 empty");
            return Optional.empty();
        }
        for (WebSearchProperties.ProviderProperties p : providers) {
            WebSearchPort port = available.get(p.getName());
            if (port == null) {
                log.warn("[WebSearch] 注册表无可用端口，跳过 provider={}", p.getName());
                continue;
            }
            Optional<WebSearchPort.WebSearchResult> result = port.search(query);
            if (result.isPresent()) {
                return result;
            }
            log.warn("[WebSearch] provider={} 失败，尝试下一源", p.getName());
        }
        return Optional.empty();
    }
}
