package com.travel.knowledge.trace;

import com.travel.aigateway.route.ModelRoutingContext;
import com.travel.common.entity.AgentTrace;
import com.travel.common.repository.AgentTraceMapper;
import com.travel.common.util.JsonUtils;
import com.travel.common.trace.SpanCollector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * knowledge 侧 Agent 追溯（F89）：RAG 检索链路记录。
 *
 * <p>同步写入 + 失败降级日志（RAG 调用频率低，可接受）；与 planning 共用
 * t_agent_trace 表。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeTraceRecorder {

    private final AgentTraceMapper agentTraceMapper;
    /** S-B5a：Span 采集（B-4；optional 注入，缺省自给实例=无 bean 也不影响 trace 主流程） */
    private SpanCollector spanCollector = new SpanCollector();

    @Autowired(required = false)
    void setSpanCollector(SpanCollector spanCollector) {
        this.spanCollector = spanCollector;
    }

    @Value("${travel.trace.enabled:true}")
    private boolean traceEnabled;

    @Value("${travel.rag.trace.model:qwen3.7-max}")
    private String modelName;

    /**
     * 在 RAG 检索外层记录追溯。
     */
    public <T> T aroundRag(String query, String ragType, Supplier<T> fn) {
        if (!traceEnabled) {
            return fn.get();
        }
        AgentTrace t = new AgentTrace();
        String requestId = UUID.randomUUID().toString();
        t.setRequestId(requestId);
        t.setTraceType("rag");
        t.setEndpoint("POST /api/v1/attractions/search");
        t.setModelName(modelName); // 先用配置默认，实际路由模型在 runWith 内捕获后覆盖
        t.setCallPath("[\"rag:" + (ragType == null || ragType.isBlank() ? "auto" : ragType) + "\"]");
        t.setInputSummary(truncate(query, 500));
        t.setStartTime(LocalDateTime.now());
        t.setStatus("RUNNING");
        // S-B5a：Span 上下文绑定——同线程 MDC 型（请求链同步执行），内层挂点经便捷对写入
        spanCollector.bindCurrent(requestId);
        try {
            // M7-6：runWith(null) 先清空 ROUTED 槽位（防线程池跨请求残留），
            // 链内 RoleRoutingChatModel.resolve 写入实际路由模型，链结束后捕获
            AtomicReference<String> routed = new AtomicReference<>();
            T result = ModelRoutingContext.runWith(null, () -> {
                T r = fn.get();
                routed.set(ModelRoutingContext.routed());
                return r;
            });
            t.setModelName(routed.get() != null ? routed.get() : modelName);
            t.setEndTime(LocalDateTime.now());
            t.setDurationMs(java.time.Duration.between(t.getStartTime(), t.getEndTime()).toMillis());
            t.setOutputLength(result instanceof List<?> list ? list.size() : 0);
            t.setStatus("SUCCESS");
            t.setSpans(spanCollector.drainSpansJson(requestId)); // S-B5a：Span 树透传（取走即清理）
            enrichRoutingColumns(t, t.getSpans()); // S-B5b：routing_ms/hedge_won 列（route span 单一事实源）
            save(t);
            return result;
        } catch (Exception e) {
            t.setEndTime(LocalDateTime.now());
            t.setDurationMs(java.time.Duration.between(t.getStartTime(), t.getEndTime()).toMillis());
            t.setStatus("FAILED");
            t.setErrorMsg(truncate(e.getMessage(), 500));
            t.setSpans(spanCollector.drainSpansJson(requestId));
            enrichRoutingColumns(t, t.getSpans());
            save(t);
            throw e;
        } finally {
            spanCollector.unbindCurrent(); // finally 解绑防线程池串号
        }
    }

    /**
     * S-B5b：routing_ms/hedge_won 列填充——route span 为单一事实源：
     * durationMs→routingMs；hedgeLaunched 且 router=hedged→hedgeWon=1，hedgeLaunched 且
     * 其他 router→0（路由胜出）；未启用对冲→null。解析失败静默（trace 主流程不受影响）。
     */
    private void enrichRoutingColumns(AgentTrace t, String spansJson) {
        if (spansJson == null) {
            return;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode arr = JsonUtils.getMapper().readTree(spansJson);
            if (!arr.isArray()) {
                return;
            }
            for (com.fasterxml.jackson.databind.JsonNode node : arr) {
                if (!"route".equals(node.path("name").asText())) {
                    continue;
                }
                t.setRoutingMs(node.path("durationMs").asLong());
                com.fasterxml.jackson.databind.JsonNode attrs = node.path("attrs");
                if (attrs.path("hedgeLaunched").asBoolean(false)) {
                    t.setHedgeWon("hedged".equals(attrs.path("router").asText()) ? 1 : 0);
                }
                break;
            }
        } catch (Exception e) {
            log.debug("[AgentTrace] routing 列解析跳过: {}", e.getMessage());
        }
    }

    private void save(AgentTrace t) {
        try {
            t.setCreatedAt(LocalDateTime.now());
            agentTraceMapper.insert(t);
        } catch (Exception e) {
            log.warn("[AgentTrace] RAG 追溯落库失败（降级）: requestId={}, error={}",
                    t.getRequestId(), e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
