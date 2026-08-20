package com.travel.crawl.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.crawl.model.AttractionRaw;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Redis Stream 执行队列（F110-B）：多实例安全（group 消费 + XACK），
 * 0/1 文件队列降级为归档（见 LocalCrawlQueue）。queue-type=redis 时启用。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "travel.crawl.queue-type", havingValue = "redis")
public class RedisCrawlQueue implements CrawlQueue {

    private static final String STREAM = "crawl:raw:stream";
    private static final String GROUP = "crawl-importers";
    private static final String FIELD = "payload";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String consumerId = "crawl-" + UUID.randomUUID().toString().substring(0, 8);

    public RedisCrawlQueue(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void enqueue(List<AttractionRaw> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        try {
            String payload = mapper.writeValueAsString(items);
            redis.opsForStream().add(
                    org.springframework.data.redis.connection.stream.StreamRecords
                            .string(Collections.singletonMap(FIELD, payload))
                            .withStreamKey(STREAM));
            log.info("[CrawlQueue] Redis 入队: items={}", items.size());
        } catch (Exception e) {
            log.warn("[CrawlQueue] Redis 入队失败: {}", e.getMessage());
        }
    }

    @Override
    public List<CrawlBatch> drain(int maxBatches) {
        ensureGroup();
        List<CrawlBatch> batches = new ArrayList<>();
        try {
            List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                    Consumer.from(GROUP, consumerId),
                    StreamReadOptions.empty().count(maxBatches),
                    StreamOffset.create(STREAM, ReadOffset.lastConsumed()));
            if (records == null) {
                return batches;
            }
            for (MapRecord<String, Object, Object> record : records) {
                Object payload = record.getValue().get(FIELD);
                if (payload == null) {
                    continue;
                }
                List<AttractionRaw> items = mapper.readValue(
                        String.valueOf(payload),
                        mapper.getTypeFactory().constructCollectionType(List.class, AttractionRaw.class));
                batches.add(new CrawlBatch(record.getId().getValue(), items == null ? List.of() : items));
            }
        } catch (Exception e) {
            log.warn("[CrawlQueue] Redis drain 失败: {}", e.getMessage());
        }
        return batches;
    }

    @Override
    public void ack(String ref) {
        try {
            redis.opsForStream().acknowledge(STREAM, GROUP, ref);
        } catch (Exception e) {
            log.warn("[CrawlQueue] Redis ack 失败: {}", e.getMessage());
        }
    }

    @Override
    public int pendingCount() {
        try {
            Long n = redis.opsForStream().size(STREAM);
            return n == null ? 0 : n.intValue();
        } catch (Exception e) {
            return 0;
        }
    }

    private void ensureGroup() {
        try {
            redis.opsForStream().createGroup(STREAM, GROUP);
        } catch (Exception e) {
            // BUSYGROUP：已存在则忽略
        }
    }
}
