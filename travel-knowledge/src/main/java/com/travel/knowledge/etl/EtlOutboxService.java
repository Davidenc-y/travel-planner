package com.travel.knowledge.etl;

import com.travel.knowledge.repository.EtlOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * DG-3b/3c：ETL 变更事件 outbox 写入服务。
 *
 * <p>三更新点中的 Import（upsert/insert 路径与 importOne）与 Enrich（回写成功处）在数据落库旁
 * 调用 {@link #writeEvent}；writeEvent 经 Mapper 加入调用方事务（同事务写 outbox），落库成功后
 * 同步 XADD {@link #STREAM_KEY} 通知消费端（DG-3c 消费组重索引）。</p>
 *
 * <p>语义（方案 §五 DG-3 2026-09-13 修订版）：outbox 行为兜底一致性通道的<b>权威记录</b>；
 * XADD 失败仅 WARN 不回滚不阻断（DG-4 --repair 与 PD-1 断言兜底）。第三点"人工补录"无应用层
 * 代码站点，其一致性由 DG-4 对账 --repair 覆盖。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EtlOutboxService {

    /** 变更事件 Stream key（消费组见 EtlChangeEventConsumer） */
    public static final String STREAM_KEY = "travel:etl:changes";

    private final EtlOutboxMapper outboxMapper;
    private final StringRedisTemplate redisTemplate;

    /** 写入一条变更事件：同事务落 outbox 行，成功后同步 XADD 通知（失败不阻断） */
    public void writeEvent(Long attractionId, String changeType) {
        EtlOutbox event = new EtlOutbox();
        event.setAttractionId(attractionId);
        event.setChangeType(changeType);
        outboxMapper.insert(event);
        publishToStream(attractionId, changeType);
        log.info("[EtlOutbox] 事件写入: attractionId={}, changeType={}", attractionId, changeType);
    }

    /** DG-3c：XADD 通知（field 含 attractionId/changeType；重复消费幂等由消费端按 attractionId upsert 承担） */
    private void publishToStream(Long attractionId, String changeType) {
        try {
            redisTemplate.opsForStream().add(STREAM_KEY, Map.of(
                    "attractionId", String.valueOf(attractionId),
                    "changeType", changeType == null ? "" : changeType));
        } catch (Exception e) {
            log.warn("[EtlOutbox] XADD 失败（outbox 行已落库，不回滚不阻断）: attractionId={}, err={}",
                    attractionId, e.getMessage());
        }
    }
}
