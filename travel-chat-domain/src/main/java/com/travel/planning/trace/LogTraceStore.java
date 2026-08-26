package com.travel.planning.trace;

import com.travel.common.entity.AgentTrace;
import com.travel.common.trace.TraceStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 日志降级存储（无表/关表环境） */
@Slf4j
@Component
@ConditionalOnProperty(name = "travel.trace.store", havingValue = "log", matchIfMissing = false)
public class LogTraceStore implements TraceStore {
    @Override
    public void save(AgentTrace trace) {
        log.info("[AgentTrace][log-store] requestId={}, type={}, path={}, durationMs={}, tokens={}, status={}",
                trace.getRequestId(), trace.getTraceType(), trace.getCallPath(),
                trace.getDurationMs(), trace.getTokenTotal(), trace.getStatus());
    }
}
