package com.travel.planning.trace;

import com.travel.common.entity.AgentTrace;
import com.travel.common.trace.TraceStore;
import com.travel.planning.repository.AgentTraceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** MySQL 追溯存储（默认实现；失败降级日志，不阻断业务） */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "travel.trace.store", havingValue = "mysql", matchIfMissing = true)
public class MysqlTraceStore implements TraceStore {

    private final AgentTraceMapper agentTraceMapper;

    @Override
    public void save(AgentTrace trace) {
        try {
            if (trace.getCreatedAt() == null) {
                trace.setCreatedAt(java.time.LocalDateTime.now());
            }
            agentTraceMapper.insert(trace);
        } catch (Exception e) {
            log.warn("[AgentTrace] 落库失败（降级日志）: requestId={}, error={}",
                    trace.getRequestId(), e.getMessage());
        }
    }
}
