package com.travel.knowledge.etl;

import com.travel.knowledge.repository.EtlOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DG-3c：ETL 变更事件消费者（Redis Stream 消费组模式，E-15 裁定通道）。
 *
 * <p>消费组 {@link #GROUP} 消费 {@link #STREAM_KEY}：每条事件按 attractionId 调
 * {@link AttractionEtlService#reindexOne}（幂等 upsert ES/Milvus）；处理成功后<b>先标记
 * outbox consumed=1（DG-3c-fix，PD-1 断言①）再 XACK</b>；异常不 ACK 留待重投
 * （方案 §五 DG-3c 语义）。目标景点已不存在视为已处理并 ACK（避免毒消息空转）。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EtlChangeEventConsumer {

    /** 变更事件 Stream key（生产端见 EtlOutboxService.STREAM_KEY） */
    public static final String STREAM_KEY = EtlOutboxService.STREAM_KEY;
    /** 消费组名 */
    public static final String GROUP = "etl-reindex-group";
    /** 本实例消费者名 */
    public static final String CONSUMER_NAME = "knowledge-1";

    private final StringRedisTemplate redisTemplate;
    /** S-E4：建组一次性标志——成功（含 BUSYGROUP=组已存在）后不再逐轮 XGROUP CREATE */
    private final java.util.concurrent.atomic.AtomicBoolean groupEnsured = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final AttractionEtlService etlService;
    /** DG-3c-fix：消费成功后回写 outbox consumed 标记 */
    private final EtlOutboxMapper outboxMapper;

    /**
     * 消费一轮（生产由 @Scheduled 轮询；单测直接调用）。
     *
     * @return 本轮成功处理（已 ACK）条数
     */
    @Scheduled(fixedDelay = 5000)
    public long consumeOnce() {
        ensureGroup();
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                Consumer.from(GROUP, CONSUMER_NAME),
                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()));
        if (records == null || records.isEmpty()) {
            return 0;
        }
        long processed = 0;
        for (MapRecord<String, Object, Object> record : records) {
            if (process(record)) {
                processed++;
            }
        }
        return processed;
    }

    /** 单条处理：重索引 → 标记 outbox consumed=1（DG-3c-fix）→ XACK；异常不 ACK 留待重投 */
    private boolean process(MapRecord<String, Object, Object> record) {
        String attractionId = String.valueOf(record.getValue().get("attractionId"));
        try {
            etlService.reindexOne(Long.parseLong(attractionId));
            // DG-3c-fix：XACK 前标记 outbox consumed=1（PD-1 断言①；markConsumed 异常同走不 ACK 重投）
            outboxMapper.markConsumed(Long.parseLong(attractionId));
            redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP, record.getId());
            log.info("[EtlChangeEventConsumer] 事件已消费并重索引: record={}, attractionId={}",
                    record.getId(), attractionId);
            return true;
        } catch (Exception e) {
            log.warn("[EtlChangeEventConsumer] 消费失败不 ACK（留待重投）: record={}, attractionId={}, err={}",
                    record.getId(), attractionId, e.getMessage());
            return false;
        }
    }

    /** 幂等建组（BUSYGROUP 视为已存在；建组从 0 起读，兜底通道不漏早期事件） */
    /** S-E4：包内可见（单测直调） */
    void ensureGroup() {
        if (groupEnsured.get()) {
            return; // 建组一次性：成功（含 BUSYGROUP）后轮询不再发 XGROUP CREATE
        }
        try {
            redisTemplate.opsForStream().createGroup(STREAM_KEY, ReadOffset.from("0"), GROUP);
            groupEnsured.set(true);
        } catch (Exception e) {
            log.debug("[EtlChangeEventConsumer] 建组跳过（通常为组已存在）: {}", e.getMessage());
            // BUSYGROUP=组已存在，同样视为建组完成（S-E4）
            groupEnsured.set(true);
        }
    }
}
