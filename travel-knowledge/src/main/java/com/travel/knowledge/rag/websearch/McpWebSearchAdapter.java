package com.travel.knowledge.rag.websearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.common.util.JsonUtils;
import com.travel.core.guard.CircuitBreaker;
import com.travel.core.guard.RateLimiter;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * M8-4：MCP 联网搜索适配器（spring-ai-starter-mcp-client，web-search.enabled=true 时装配）。
 *
 * <p>设计：</p>
 * <ul>
 *   <li>不自动注册为全局工具（Spring AI MCP 自动装配已禁用），仅本适配器按需调用——
 *       LLM 没有自由搜索工具，杜绝注入面扩大；</li>
 *   <li>M8-9b：改用 MCP Java SDK 在后台线程自行建立 stdio 连接。原 Spring AI MCP
 *       client 自动装配在启动期 initialize 失败会直接终止应用（20s 超时后抛异常），
 *       无法满足“web-search 灰度不阻断启动”；后台建连失败仅降级为空搜索工具。</li>
 *   <li>限频（travel-core RateLimiter per-minute）+ 日配额 + 熔断（CircuitBreaker
 *       "web_search"）+ 超时硬上限，失败/超时/限频一律返回 empty；</li>
 *   <li>搜索原文只进抽取器（WebEnrichExtractor），不进 prompt。</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "travel.rag.web-search", name = "enabled", havingValue = "true")
public class McpWebSearchAdapter implements WebSearchPort {

    private static final ExecutorService SEARCH_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    private final WebSearchProperties properties;
    private final RateLimiter rateLimiter;
    private final CircuitBreaker circuitBreaker;
    private final AtomicInteger dailyCount = new AtomicInteger(0);
    private volatile LocalDate dailyDate = LocalDate.now();
    /** M8-9c：客户端构建工厂（测试可注入，验证初始化失败时 close 不泄漏） */
    private final McpClientFactory clientFactory;
    /** 后台连接成功后的 MCP 同步客户端（null=未就绪/连接失败，search 降级） */
    private volatile McpSyncClient mcpClient;
    /** 已选中的搜索工具名（duckduckgo_web_search） */
    private volatile String searchToolName;

    @Autowired
    public McpWebSearchAdapter(WebSearchProperties properties) {
        this(properties, McpWebSearchAdapter::buildClient);
    }

    /** 测试注入：可替换客户端构建逻辑（默认走 MCP Java SDK） */
    McpWebSearchAdapter(WebSearchProperties properties, McpClientFactory clientFactory) {
        this.properties = properties;
        this.rateLimiter = new RateLimiter(Math.max(1, properties.getRateLimitPerMinute()));
        this.circuitBreaker = new CircuitBreaker(3, 60_000, 30_000);
        this.clientFactory = clientFactory;
        connectAsync();
    }

    /** MCP 同步客户端构建：Windows 必须 cmd /c 包装 npx；超时按冷启动放宽 */
    private static McpSyncClient buildClient(WebSearchProperties properties) throws Exception {
        List<String> cmd = properties.getMcpCommand() == null || properties.getMcpCommand().isEmpty()
                ? List.of("cmd", "/c", "npx", "-y", "duckduckgo-mcp-server")
                : properties.getMcpCommand();
        ServerParameters params = ServerParameters.builder(cmd.get(0))
                .args(cmd.size() > 1 ? cmd.subList(1, cmd.size()) : List.of())
                .build();
        return McpClient.sync(new StdioClientTransport(params, McpJsonMapper.createDefault()))
                .clientInfo(new McpSchema.Implementation("travel-knowledge", "1.0"))
                .requestTimeout(Duration.ofSeconds(30))
                .initializationTimeout(Duration.ofSeconds(60))
                .build();
    }

    /**
     * M8-9b：后台线程建立 MCP stdio 连接。所有异常在内部捕获并降级，
     * 构造与启动不抛异常；连接成功后由 search() 按需调用。
     */
    private void connectAsync() {
        Thread.ofVirtual().name("web-search-mcp-connect").start(() -> {
            McpSyncClient client = null;
            try {
                client = clientFactory.create(properties);
                client.initialize();
                List<McpSchema.Tool> tools = client.listTools().tools();
                String target = tools.stream()
                        .map(McpSchema.Tool::name)
                        .filter(this::isSearchTool)
                        .findFirst()
                        .orElse(null);
                if (target == null) {
                    log.warn("[WebSearch] MCP server 无搜索工具（共 {} 个），关闭连接并降级", tools.size());
                    client.close();
                    return;
                }
                this.mcpClient = client;
                this.searchToolName = target;
                log.info("[WebSearch] MCP 适配器就绪: 候选搜索工具 {} 个",
                        tools.stream().filter(t -> isSearchTool(t.name())).count());
            } catch (Exception e) {
                // M8-9c：初始化失败必须关闭 client，否则 npx/stdio 子进程泄漏
                if (client != null) {
                    try {
                        client.close();
                    } catch (Exception closeErr) {
                        log.debug("[WebSearch] 关闭失败客户端失败: {}", closeErr.getMessage());
                    }
                }
                log.warn("[WebSearch] MCP 连接失败，自动降级为无搜索工具: {}", e.getMessage());
            }
        });
    }

    private boolean isSearchTool(String name) {
        return name != null && (name.contains("web_search") || name.contains("search"));
    }

    @Override
    public Optional<WebSearchResult> search(String query) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        if (!allowDaily()) {
            log.warn("[WebSearch] 日配额已达上限 {}，熔断至次日", properties.getDailyQuota());
            return Optional.empty();
        }
        if (!rateLimiter.tryAcquire("web_search")) {
            log.warn("[WebSearch] 限频拒绝（{} 次/分钟）: query={}",
                    properties.getRateLimitPerMinute(), query);
            return Optional.empty();
        }
        McpSyncClient client = mcpClient;
        String toolName = searchToolName;
        if (client == null || toolName == null) {
            log.warn("[WebSearch] 无可用搜索工具（MCP server 未连接/连接中）: query={}", query);
            return Optional.empty();
        }
        try {
            return circuitBreaker.call("web_search", () -> {
                CompletableFuture<String> future = CompletableFuture
                        .supplyAsync(() -> invokeTool(client, toolName, query), SEARCH_EXECUTOR)
                        .orTimeout(Math.max(100, properties.getTimeoutMs()), TimeUnit.MILLISECONDS);
                String raw = future.join();
                return parseResult(raw).map(r -> {
                    log.info("[WebSearch] 搜索成功: query={}, title={}", query, r.title());
                    return r;
                });
            });
        } catch (Exception e) {
            log.warn("[WebSearch] 搜索失败/超时，静默降级 empty: query={}, err={}",
                    query, e.getMessage());
            return Optional.empty();
        }
    }

    private String invokeTool(McpSyncClient client, String toolName, String query) {
        try {
            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest(toolName, Map.of("query", query)));
            if (result.isError() != null && result.isError()) {
                throw new IllegalStateException("MCP 工具返回错误");
            }
            StringBuilder sb = new StringBuilder();
            for (McpSchema.Content content : result.content()) {
                if (content instanceof McpSchema.TextContent textContent) {
                    sb.append(textContent.text());
                }
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MCP 工具调用失败", e);
        }
    }

    /** 解析 MCP 工具输出：数组取首条 / 对象取字段 / 否则原文作 snippet */
    private static Optional<WebSearchResult> parseResult(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode node = JsonUtils.getMapper().readTree(raw);
            JsonNode first = node.isArray() && !node.isEmpty() ? node.get(0) : node;
            String title = first.path("title").asText("");
            String snippet = first.hasNonNull("abstract")
                    ? first.path("abstract").asText()
                    : first.hasNonNull("snippet") ? first.path("snippet").asText() : "";
            String url = first.hasNonNull("url") ? first.path("url").asText()
                    : first.hasNonNull("link") ? first.path("link").asText() : "";
            if (!title.isBlank() || !snippet.isBlank()) {
                return Optional.of(new WebSearchResult(
                        title, snippet, url, java.time.LocalDateTime.now().toString()));
            }
        } catch (Exception e) {
            log.debug("[WebSearch] 输出非 JSON，按原文 snippet 处理: {}", e.getMessage());
        }
        String text = raw.trim();
        return text.isEmpty() ? Optional.empty()
                : Optional.of(new WebSearchResult("", text.length() > 500 ? text.substring(0, 500) : text,
                        "", java.time.LocalDateTime.now().toString()));
    }

    private boolean allowDaily() {
        LocalDate today = LocalDate.now();
        if (!today.equals(dailyDate)) {
            dailyDate = today;
            dailyCount.set(0);
        }
        return dailyCount.incrementAndGet() <= properties.getDailyQuota();
    }

    /** 单测注入：绕过后台建连，直接挂载 mock 客户端与工具名 */
    void attachForTest(McpSyncClient client, String toolName) {
        this.mcpClient = client;
        this.searchToolName = toolName;
    }

    @PreDestroy
    void close() {
        McpSyncClient client = mcpClient;
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.debug("[WebSearch] 关闭 MCP 客户端失败: {}", e.getMessage());
            }
        }
    }

    /** MCP 客户端构建工厂（包内可见，供单测注入） */
    @FunctionalInterface
    interface McpClientFactory {
        McpSyncClient create(WebSearchProperties properties) throws Exception;
    }
}
