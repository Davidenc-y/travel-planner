package com.travel.planning.service.writeback;

import com.travel.common.config.GrayReleaseManager;
import com.travel.planning.service.ItineraryDetailCache;
import com.travel.planning.service.ItinerarySliceWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * HC-5：writeback ETL 类副作用的异步消费组（Stream {@link #STREAM_KEY}，E-15 选型）。
 *
 * <p>消费语义：每条事件执行 会话知识切片写回（writeAfterGenerated）+ 行程详情缓存失效（evict）
 * 后 XACK；<b>消费失败不 ACK 留待重投</b>；同一事件累计消费失败 ≥{@link #DLQ_AFTER_DELIVERIES}
 * 次转死信日志 <b>[WritebackDLQ]</b> 并 ACK（终止重投循环，人工按日志处置）。
 * 幂等性：切片写回与缓存 evict 均可安全重复执行。灰度暂停开关
 * {@code gray.writeback-consumer.enabled}（D-2b，yml 显式 true=现状消费行为；false=轮询头
 * 直接空转返回 0，Stream 积压人工应急门）。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WritebackEventConsumer {

    /** writeback 事件 Stream key（生产端见 WritebackEventPublisher.STREAM_KEY） */
    public static final String STREAM_KEY = WritebackEventPublisher.STREAM_KEY;
    /** 消费组名 */
    public static final String GROUP = "writeback-worker";
    /** 本实例消费者名 */
    public static final String CONSUMER_NAME = "planning-1";
    /** 消费失败重投上限（达到即转死信日志并 ACK） */
    private static final int DLQ_AFTER_DELIVERIES = 3;
    /** D-2b 灰度暂停键（与 GrayReleaseManager.KNOWN_KEYS 登记字面一致） */
    public static final String GRAY_KEY = "gray.writeback-consumer.enabled";

    private final StringRedisTemplate redisTemplate;
    /** S-E4：建组一次性标志——成功（含 BUSYGROUP=组已存在）后不再逐轮 XGROUP CREATE */
    private final java.util.concurrent.atomic.AtomicBoolean groupEnsured = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final ItinerarySliceWriter sliceWriter;
    private final ItineraryDetailCache itineraryDetailCache;
    /** D-2b 灰度暂停门（enabled() 键缺失回落 false，故 yml 须显式 true） */
    private final GrayReleaseManager gray;

    /**
     * 消费一轮（生产由 @Scheduled 轮询；单测直接调用）。
     *
     * @return 本轮成功处理（已 ACK）条数
     */
    @Scheduled(fixedDelay = 2000)
    public long consumeOnce() {
        if (!gray.enabled(GRAY_KEY)) {
            return 0;
        }
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

    /** 单条处理：切片写回 + 缓存失效 → XACK；失败不 ACK 重投，达上限转死信日志并 ACK */
    private boolean process(MapRecord<String, Object, Object> record) {
        Map<Object, Object> fields = record.getValue();
        String sessionId = String.valueOf(fields.getOrDefault("sessionId", ""));
        long itineraryId;
        String content = String.valueOf(fields.getOrDefault("content", ""));
        try {
            itineraryId = Long.parseLong(String.valueOf(fields.get("itineraryId")));
        } catch (Exception parseError) {
            // 载荷不可解析属不可恢复错误：转死信日志并 ACK，避免永久毒消息
            log.error("[WritebackDLQ] 载荷不可解析，ACK 丢弃: record={}, fields={}", record.getId(), fields);
            redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP, record.getId());
            return true;
        }
        try {
            sliceWriter.writeAfterGenerated(sessionId, itineraryId, content);
            if (itineraryDetailCache != null) {
                itineraryDetailCache.evict(itineraryId);
            }
            redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP, record.getId());
            log.info("[WritebackConsumer] 事件已消费: record={}, itineraryId={}", record.getId(), itineraryId);
            return true;
        } catch (Exception e) {
            long attempts = deliveryCount(record.getId());
            if (attempts >= DLQ_AFTER_DELIVERIES) {
                log.error("[WritebackDLQ] 消费失败已达 {} 次，转死信日志并 ACK（人工按日志处置）: record={}, "
                                + "itineraryId={}, error={}", attempts, record.getId(), itineraryId, e.getMessage());
                redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP, record.getId());
                // 已转死信并 ACK：计为已处理（终止重投循环，人工按 [WritebackDLQ] 日志处置）
                return true;
            }
            log.warn("[WritebackConsumer] 消费失败不 ACK（留待重投 {}/{}）: record={}, itineraryId={}, error={}",
                    attempts, DLQ_AFTER_DELIVERIES, record.getId(), itineraryId, e.getMessage());
            return false;
        }
    }

    /** 查询该事件在 PEL 中的累计投递次数（查询失败按 1 处理——首次投递语义）。 */
    private long deliveryCount(RecordId id) {
        try {
            PendingMessages pending = redisTemplate.opsForStream().pending(
                    STREAM_KEY, GROUP, Range.closed(id.getValue(), id.getValue()), 10);
            if (pending != null) {
                for (PendingMessage pm : pending) {
                    if (id.equals(pm.getId())) {
                        return pm.getTotalDeliveryCount();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[WritebackConsumer] PEL 查询失败（按首次投递处理）: {}", e.getMessage());
        }
        return 1;
    }

    /** 幂等建组（BUSYGROUP 视为已存在；建组从 0 起读，兜底通道不漏早期事件）。 */
    /** S-E4：包内可见（单测直调） */
    void ensureGroup() {
        if (groupEnsured.get()) {
            return; // 建组一次性：成功（含 BUSYGROUP）后轮询不再发 XGROUP CREATE
        }
        try {
            redisTemplate.opsForStream().createGroup(STREAM_KEY, ReadOffset.from("0"), GROUP);
            groupEnsured.set(true);
        } catch (Exception e) {
            log.debug("[WritebackConsumer] 建组跳过（通常为组已存在）: {}", e.getMessage());
            // BUSYGROUP=组已存在，同样视为建组完成（S-E4）
            groupEnsured.set(true);
        }
    }
}
