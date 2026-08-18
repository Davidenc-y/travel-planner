package com.travel.planning.trace;

import com.travel.common.entity.AgentTrace;
import com.travel.common.trace.TraceStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent 追溯采集器（F89）：begin/end/fail + 异步批量落库。
 *
 * <p>采集端同步开销仅对象分配与队列入队；落库在后台虚拟线程批量执行，
 * 队列满时丢弃并告警（高可用：不阻塞业务）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentTraceCollector {

    private final TraceStore traceStore;
    private final TraceProperties properties;

    private LinkedBlockingQueue<AgentTrace> queue;
    private ScheduledExecutorService scheduler;
    private final AtomicLong dropped = new AtomicLong();

    @PostConstruct
    public void init() {
        if (!properties.isEnabled()) {
            log.info("[AgentTrace] 追溯已禁用（travel.trace.enabled=false）");
            return;
        }
        queue = new LinkedBlockingQueue<>(Math.max(1, properties.getBufferSize()));
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agent-trace-flusher");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::flush, properties.getFlushIntervalMs(),
                properties.getFlushIntervalMs(), TimeUnit.MILLISECONDS);
        log.info("[AgentTrace] 采集器启动: store={}, buffer={}, flush={}ms",
                properties.getStore(), properties.getBufferSize(), properties.getFlushIntervalMs());
    }

    /** 结束一次调用：填充结束时间/耗时/token/路径/状态并入队 */
    public void end(TraceContext.Holder holder, String status, String errorMsg) {
        if (!properties.isEnabled() || holder == null) {
            return;
        }
        AgentTrace t = holder.trace;
        LocalDateTime now = LocalDateTime.now();
        t.setEndTime(now);
        t.setDurationMs(java.time.Duration.between(t.getStartTime(), now).toMillis());
        t.setTokenTotal(safeInt(holder.totalTokens));
        t.setTokenPrompt(safeInt(holder.promptTokens));
        t.setTokenCompletion(safeInt(holder.completionTokens));
        t.setCallPath(holder.path.isEmpty() ? null
                : com.travel.common.util.JsonUtils.toJson(holder.path));
        t.setStatus(status);
        t.setErrorMsg(errorMsg);
        if (!queue.offer(t)) {
            dropped.incrementAndGet();
            log.warn("[AgentTrace] 缓冲队列已满，丢弃追溯: requestId={}, dropped={}",
                    t.getRequestId(), dropped.get());
        }
    }

    private void flush() {
        if (queue == null || queue.isEmpty()) {
            return;
        }
        List<AgentTrace> batch = new ArrayList<>();
        queue.drainTo(batch, Math.max(1, properties.getBatchSize()));
        for (AgentTrace t : batch) {
            traceStore.save(t);
        }
    }

    @PreDestroy
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        flush();
    }

    private static int safeInt(long v) {
        return v > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) v;
    }
}
