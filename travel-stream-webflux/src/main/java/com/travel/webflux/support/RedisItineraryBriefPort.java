package com.travel.webflux.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.planning.memory.anchor.ItineraryBrief;
import com.travel.planning.memory.anchor.ItineraryBriefPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * B2.2：{@link ItineraryBriefPort} Redis 读取实现（按 2026-09-11 01:50 审计修订版 (a) 落位）——
 * gray.brief.redis.enabled=true 时以 @Primary 装配，优先读 planning 侧 B2.1 写入的
 * {@code travel:brief:{sessionId}} 快照（TTL 30 分钟）；映射缺失 / Redis miss / 异常 /
 * 反序列化失败 / id 一致性守卫不等 → 一律回退委托 {@link WebfluxChatSupportBridge}
 * （桥保持在装配链上不删不改；开关关闭时本 bean 不装配 = 全桥现状）。
 *
 * <p>sessionId 来源（接口契约 briefOf 不携带）：覆写 {@code findSessionItineraryIds}
 * 委托桥时顺带记录 id→sessionId 进程内映射（ConcurrentHashMap，上限 1024 覆写式更新
 * 防泄漏，无后台线程）。进程冷态映射未暖 / TTL 过期 → 桥（其 briefOf 经 8081 端点走
 * ItineraryBriefPortImpl，本身就会补写 Redis 快照，自愈闭环）。</p>
 */
@Slf4j
@Primary
@Component
@ConditionalOnProperty(name = "gray.brief.redis.enabled", havingValue = "true")
public class RedisItineraryBriefPort implements ItineraryBriefPort {

    /** 快照键前缀（与 B2.1 BriefRedisSnapshotWriter 同名约定） */
    static final String KEY_PREFIX = "travel:brief:";

    /** id→session 映射上限（覆写式更新防泄漏，无后台线程） */
    static final int MAP_CAP = 1024;

    private final WebfluxChatSupportBridge bridge;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<Long, String> sessionByItineraryId = new ConcurrentHashMap<>();

    public RedisItineraryBriefPort(WebfluxChatSupportBridge bridge, StringRedisTemplate redisTemplate) {
        this.bridge = bridge;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public List<Long> findSessionItineraryIds(String sessionId) {
        List<Long> ids = bridge.findSessionItineraryIds(sessionId);
        if (sessionId != null && !sessionId.isBlank() && ids != null && !ids.isEmpty()) {
            if (sessionByItineraryId.size() >= MAP_CAP) {
                sessionByItineraryId.clear();
            }
            for (Long id : ids) {
                if (id != null) {
                    sessionByItineraryId.put(id, sessionId);
                }
            }
        }
        return ids;
    }

    @Override
    public Optional<ItineraryBrief> briefOf(Long userId, Long itineraryId) {
        String sessionId = itineraryId == null ? null : sessionByItineraryId.get(itineraryId);
        ItineraryBrief hit = null;
        if (sessionId != null) {
            try {
                String json = redisTemplate.opsForValue().get(KEY_PREFIX + sessionId);
                if (json != null && !json.isBlank()) {
                    ItineraryBrief parsed = mapper.readValue(json, ItineraryBrief.class);
                    // 一致性守卫：会话快照是最近一次装配的 brief，多锚定场景可能非本次请求的行程
                    if (parsed != null && itineraryId.equals(parsed.id())) {
                        hit = parsed;
                    }
                }
            } catch (Exception e) {
                // Redis miss / 异常 / 反序列化失败 → 统一回退桥（下方 miss 日志一行覆盖）
            }
        }
        if (hit != null) {
            log.debug("[BriefRedisPort] hit key={}", KEY_PREFIX + sessionId);
            return Optional.of(hit);
        }
        log.debug("[BriefRedisPort] miss 回退桥: id={}", itineraryId);
        return bridge.briefOf(userId, itineraryId);
    }
}
