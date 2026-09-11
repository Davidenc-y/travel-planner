package com.travel.planning.service.anchor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.planning.memory.anchor.ItineraryBrief;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * B2.1：{@link ItineraryBrief} Redis 快照旁路写入器——briefOf 成功组装后写
 * {@code travel:brief:{sessionId}}（JSON，TTL 30 分钟），供 B2.2 webflux 侧
 * RedisItineraryBriefPort 读取。
 *
 * <p>旁路语义：写入失败仅 log.warn 一行，不抛出、不阻断 briefOf 主流程；
 * 无 Redis 连接时同样静默（set 异常走同一 catch）。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Component
public class BriefRedisSnapshotWriter {

    /** 快照键前缀（B2.2 webflux 侧按同名约定读取） */
    static final String KEY_PREFIX = "travel:brief:";

    /** 快照 TTL（30 分钟，随每次 briefOf 组装刷新） */
    static final Duration TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BriefRedisSnapshotWriter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 旁路写快照：sessionId 空白 / brief 为 null 直接跳过；任何异常仅 log.warn 静默降级。
     */
    public void writeSnapshot(String sessionId, ItineraryBrief brief) {
        if (sessionId == null || sessionId.isBlank() || brief == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(brief);
            redisTemplate.opsForValue().set(KEY_PREFIX + sessionId, json, TTL);
        } catch (Exception e) {
            log.warn("[BriefRedisSnapshot] 写入失败 sessionId={}: {}", sessionId, e.getMessage());
        }
    }
}
