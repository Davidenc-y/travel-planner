package com.travel.knowledge.rag.websearch;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * M8-4：联网搜索兜底配置（默认关，灰度开启）。
 *
 * <p>对应 yml：{@code travel.rag.web-search.*}。仅当本地结构化字段缺失且意图需要该字段时
 * 触发（缺失检测是确定性前置），限频/超时/熔断复用 travel-core 治理组件。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.rag.web-search")
public class WebSearchProperties {

    /** 总开关（false = Noop 直通，行为等价 Phase 1/2/3） */
    private boolean enabled = false;

    /** 单次搜索超时（SSE 流式路径延迟保护） */
    private long timeoutMs = 1500;

    /** 每分钟限频（travel-core RateLimiter） */
    private int rateLimitPerMinute = 10;

    /** 日配额（超限熔断至次日；本地内存计数） */
    private int dailyQuota = 100;

    /** 触发补全的字段（openHours/ticketPrice） */
    private List<String> enrichFields = new ArrayList<>(List.of("openHours", "ticketPrice"));

    /** M8-5：回写数据库开关（默认 false，先观测抽取质量再开启） */
    private boolean writebackEnabled = false;

    /** M9-2：补全模式（sync=现状同步补全；async=本轮回 null + 后台回写） */
    private String fillMode = "sync";

    /** async 补全模式判断（大小写不敏感） */
    public boolean isAsyncFillMode() {
        return "async".equalsIgnoreCase(fillMode);
    }

    /** M9-2：搜索源注册表（priority 升序；providers 为空时保留 MCP duckduckgo 传统语义） */
    private List<ProviderProperties> providers = new ArrayList<>();

    /**
     * 指定源是否在总开关开启时可用。
     * providers 为空 = 传统单源语义（duckduckgo/MCP 受总开关控制）；
     * providers 非空 = 总开关 && 该源 enabled。
     */
    public boolean isProviderEnabled(String name) {
        if (!enabled || name == null || name.isBlank()) {
            return false;
        }
        if (providers == null || providers.isEmpty()) {
            return "duckduckgo".equals(name);
        }
        return providers.stream()
                .anyMatch(p -> name.equals(p.getName()) && p.isEnabled());
    }

    public Optional<ProviderProperties> findProvider(String name) {
        if (providers == null || name == null) {
            return Optional.empty();
        }
        return providers.stream()
                .filter(p -> name.equals(p.getName()))
                .findFirst();
    }

    /** enabled 且已排序（priority 升序）的 provider 配置列表 */
    public List<ProviderProperties> enabledProviders() {
        if (providers == null || providers.isEmpty()) {
            return List.of();
        }
        return providers.stream()
                .filter(ProviderProperties::isEnabled)
                .sorted(Comparator.comparingInt(p -> Math.max(1, p.getPriority())))
                .toList();
    }

    /** M9-2：搜索源描述符 */
    @Data
    public static class ProviderProperties {

        /** tavily / duckduckgo / bocha（预留） */
        private String name = "";

        private boolean enabled = false;

        /** API Key 环境变量名（如 TAVILY_API_KEY；缺失视为禁用并告警） */
        private String apiKeyEnv = "";

        /** M13-1：API Key 直连值（仅允许本地未提交配置；优先于 apiKeyEnv） */
        private String apiKey = "";

        /** HTTP 端点（Tavily/博查直连）；duckduckgo/MCP 可不填 */
        private String baseUrl = "";

        /** 单次请求超时（毫秒；async 模式可放宽到 10s 量级） */
        private long timeoutMs = 10_000L;

        /** 选择顺序（1=primary；升序优先） */
        private int priority = 99;
    }

    /**
     * M8-9b：MCP stdio 启动命令（适配器后台建连使用；失败自动降级不阻断启动）。
     * 默认 Windows 命令（Java ProcessBuilder 无法直接执行 npx.cmd，必须 cmd /c 包装）；
     * Linux/macOS 可覆盖为 ["npx", "-y", "duckduckgo-mcp-server"]。
     */
    private List<String> mcpCommand =
            new ArrayList<>(List.of("cmd", "/c", "npx", "-y", "duckduckgo-mcp-server"));
}
