package com.travel.planning.service.monitoring;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.travel.common.entity.AgentTrace;
import com.travel.common.repository.AgentTraceMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * AX-1：t_agent_trace 保留策略——AW 审计实测表 7 天 10.6 万行且全库无清理任务，
 * 看板聚合成本随表单调增长。按批删除超过保留期的行（created_at 有索引；看板窗口
 * 上限 30 天 < 默认保留 35 天，聚合口径不受影响；两写入方 planning/knowledge 共库，
 * 本任务在 planning 单点清理即覆盖全表）。
 *
 * <p>键（E-33 风格代码内默认）：travel.trace.retention-days 默认 35（<=0 关闭）；
 * travel.trace.cleanup-batch 默认 5000（单批上限，防长事务）；travel.trace.cleanup-cron
 * 默认 04:30（错开 04:00 MemoryConsolidation 批扫）。</p>
 */
@Slf4j
@Component
public class TraceRetentionJob {

    private final AgentTraceMapper agentTraceMapper;
    private final int retentionDays;
    private final int cleanupBatch;

    public TraceRetentionJob(AgentTraceMapper agentTraceMapper,
                             @Value("${travel.trace.retention-days:35}") int retentionDays,
                             @Value("${travel.trace.cleanup-batch:5000}") int cleanupBatch) {
        this.agentTraceMapper = agentTraceMapper;
        this.retentionDays = retentionDays;
        this.cleanupBatch = cleanupBatch;
    }

    @Scheduled(cron = "${travel.trace.cleanup-cron:0 30 4 * * *}")
    public void cleanup() {
        if (retentionDays <= 0 || cleanupBatch <= 0) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        long total = 0;
        int affected;
        do {
            affected = agentTraceMapper.delete(new QueryWrapper<AgentTrace>()
                    .lt("created_at", cutoff)
                    .last("LIMIT " + cleanupBatch));
            total += affected;
        } while (affected >= cleanupBatch);
        if (total > 0) {
            log.info("[TraceRetention] 已删除 {} 行早于 {}（保留 {} 天）", total, cutoff, retentionDays);
        }
    }
}
