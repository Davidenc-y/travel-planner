package com.travel.knowledge.rag.websearch;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

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

    /**
     * M8-9b：MCP stdio 启动命令（适配器后台建连使用；失败自动降级不阻断启动）。
     * 默认 Windows 命令（Java ProcessBuilder 无法直接执行 npx.cmd，必须 cmd /c 包装）；
     * Linux/macOS 可覆盖为 ["npx", "-y", "duckduckgo-mcp-server"]。
     */
    private List<String> mcpCommand =
            new ArrayList<>(List.of("cmd", "/c", "npx", "-y", "duckduckgo-mcp-server"));
}
