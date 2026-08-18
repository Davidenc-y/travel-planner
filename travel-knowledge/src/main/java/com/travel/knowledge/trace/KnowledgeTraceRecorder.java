package com.travel.knowledge.trace;

import com.travel.common.entity.AgentTrace;
import com.travel.knowledge.repository.AgentTraceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
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

    @Value("${travel.trace.enabled:true}")
    private boolean traceEnabled;

    @Value("${spring.ai.dashscope.chat.options.model:qwen3.7-max}")
    private String modelName;

    /**
     * 在 RAG 检索外层记录追溯。
     */
    public <T> T aroundRag(String query, String ragType, Supplier<T> fn) {
        if (!traceEnabled) {
            return fn.get();
        }
        AgentTrace t = new AgentTrace();
        t.setRequestId(UUID.randomUUID().toString());
        t.setTraceType("rag");
        t.setEndpoint("POST /api/v1/attractions/search");
        t.setModelName(modelName);
        t.setCallPath("[\"rag:" + (ragType == null || ragType.isBlank() ? "auto" : ragType) + "\"]");
        t.setInputSummary(truncate(query, 500));
        t.setStartTime(LocalDateTime.now());
        t.setStatus("RUNNING");
        try {
            T result = fn.get();
            t.setEndTime(LocalDateTime.now());
            t.setDurationMs(java.time.Duration.between(t.getStartTime(), t.getEndTime()).toMillis());
            t.setOutputLength(result instanceof List<?> list ? list.size() : 0);
            t.setStatus("SUCCESS");
            save(t);
            return result;
        } catch (Exception e) {
            t.setEndTime(LocalDateTime.now());
            t.setDurationMs(java.time.Duration.between(t.getStartTime(), t.getEndTime()).toMillis());
            t.setStatus("FAILED");
            t.setErrorMsg(truncate(e.getMessage(), 500));
            save(t);
            throw e;
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
