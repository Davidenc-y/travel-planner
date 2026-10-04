package com.travel.planning.service.monitoring;

import com.rabbitmq.client.AMQP;
import com.travel.planning.repository.DataQualityMapper;
import com.travel.planning.service.writeback.RabbitWritebackConfig;
import com.travel.planning.service.writeback.WritebackEventConsumer;
import com.travel.planning.service.writeback.WritebackEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
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
 * <p>Redis 异常 fail-open（本批惯例）：stream 段降级为 error 标记，端点仍返回可用维度。
 * AZ-3：rabbit writeback 队列深度段（passive declare 只读；type!=rabbit 或连接异常→段=null fail-open）。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Service
public class DataQualityService {

    private final DataQualityMapper dataQualityMapper;
    private final StringRedisTemplate redisTemplate;
    private final RabbitTemplate rabbitTemplate;
    /** AZ-3：eventbus 类型门（缺省 redis=现状零变更；type!=rabbit 时深度段=null，P0-AZ⑸）。 */
    private final String eventbusType;

    public DataQualityService(DataQualityMapper dataQualityMapper, StringRedisTemplate redisTemplate,
            RabbitTemplate rabbitTemplate,
            @Value("${travel.eventbus.type:redis}") String eventbusType) {
        this.dataQualityMapper = dataQualityMapper;
        this.redisTemplate = redisTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.eventbusType = eventbusType;
    }

    public Map<String, Object> dataQualitySnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();

        Map<String, Object> etlOutbox = new LinkedHashMap<>();
        etlOutbox.put("unconsumed", dataQualityMapper.countEtlOutboxUnconsumed());
        etlOutbox.put("total", dataQualityMapper.countEtlOutboxTotal());
        snapshot.put("etlOutbox", etlOutbox);

        snapshot.put("writebackStream", writebackStreamSection());
        snapshot.put("rabbitWriteback", rabbitWritebackDepth()); // AZ-3：type!=rabbit/异常→null（fail-open）

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

    /** AZ-3：rabbit writeback 队列深度（只读 passive declare，零管理口依赖）。
     * type!=rabbit 或连接异常 → 段=null（fail-open，不阻断看板）；execute 惰性建连，
     * type=redis 时本方法不触碰 rabbit（P0-AZ⑸）。 */
    private Map<String, Object> rabbitWritebackDepth() {
        if (!"rabbit".equals(eventbusType)) {
            return null;
        }
        try {
            AMQP.Queue.DeclareOk main = rabbitTemplate.execute(ch -> ch.queueDeclarePassive(RabbitWritebackConfig.QUEUE));
            AMQP.Queue.DeclareOk dlq = rabbitTemplate.execute(ch -> ch.queueDeclarePassive(RabbitWritebackConfig.DLQ));
            Map<String, Object> section = new LinkedHashMap<>();
            section.put("mainDepth", main == null ? -1L : (long) main.getMessageCount());
            section.put("dlqDepth", dlq == null ? -1L : (long) dlq.getMessageCount());
            section.put("consumers", main == null ? -1 : main.getConsumerCount());
            return section;
        } catch (Exception ex) {
            log.warn("[DataQuality] rabbit writeback depth unavailable, fail-open: err={}", ex.getMessage());
            return null;
        }
    }
}
