package com.travel.planning.service;

import com.travel.planning.repository.DataQualityMapper;
import com.travel.planning.service.writeback.WritebackEventConsumer;
import com.travel.planning.service.writeback.WritebackEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * E-5a：数据质量端点服务（纯读聚合）。
 *
 * <p>三个数据维度：①ETL outbox 未消费/全量计数（t_etl_outbox，consumed=0 简单查询）；
 * ②writeback Redis Stream 积压（XLEN 总量+消费组 PEL 计数）——死信口径按决策 F 案 A
 * 裁决=PEL 计数（zero-schema 纯读；死信本体沿 [WritebackDLQ] 日志人工处置惯例，
 * 案 B"日志落小表"需 DDL+消费钩子，证据裁决不采纳）；③三端对账（check_consistency）
 * 机制当前不存在（全仓零命中，R19b），显式返回 not-implemented 标记不伪造对账数据。</p>
 *
 * <p>Redis 异常 fail-open（本批惯例）：stream 段降级为 error 标记，端点仍返回可用维度。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataQualityService {

    private final DataQualityMapper dataQualityMapper;
    private final StringRedisTemplate redisTemplate;

    public Map<String, Object> dataQualitySnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();

        Map<String, Object> etlOutbox = new LinkedHashMap<>();
        etlOutbox.put("unconsumed", dataQualityMapper.countEtlOutboxUnconsumed());
        etlOutbox.put("total", dataQualityMapper.countEtlOutboxTotal());
        snapshot.put("etlOutbox", etlOutbox);

        snapshot.put("writebackStream", writebackStreamSection());

        snapshot.put("consistencyCheck", "not-implemented");
        return snapshot;
    }

    private Map<String, Object> writebackStreamSection() {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("key", WritebackEventPublisher.STREAM_KEY);
        section.put("group", WritebackEventConsumer.GROUP);
        section.put("deadLetterPolicy", "F-案A PEL 计数（zero-schema）；死信本体沿 [WritebackDLQ] 日志人工处置");
        try {
            section.put("length", redisTemplate.opsForStream().size(WritebackEventPublisher.STREAM_KEY));
            PendingMessagesSummary pending =
                    redisTemplate.opsForStream().pending(WritebackEventPublisher.STREAM_KEY, WritebackEventConsumer.GROUP);
            section.put("pending", pending == null ? 0L : pending.getTotalPendingMessages());
        } catch (Exception ex) {
            log.warn("[DataQuality] writeback stream metrics unavailable, fail-open: err={}", ex.getMessage());
            section.put("error", "stream metrics unavailable");
        }
        return section;
    }
}
