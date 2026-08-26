package com.travel.planning.memory.pipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.travel.common.util.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * M6-36：聊天轮次中断标记与断点快照（Redis 临时态，不落库）。
 *
 * <ul>
 *   <li>中断标记 {@code chat:interrupt:{clientMessageId}}（TTL 5min）：路由前/落库前
 *       检查，命中则终止并保持幂等 FAILED；</li>
 *   <li>断点快照 {@code chat:breakpoint:{sessionId}:{clientMessageId}}（TTL 30min，
 *       JSON）：步骤 3~7 产物，重试时跳过上下文组装直达路由。</li>
 * </ul>
 *
 * <p>与 SessionMemoryServiceImpl 的 Redis 用法一致（StringRedisTemplate + JsonUtils）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatBreakpointStore {

    private static final String INTERRUPT_PREFIX = "chat:interrupt:";
    private static final String BREAKPOINT_PREFIX = "chat:breakpoint:";
    private static final Duration INTERRUPT_TTL = Duration.ofMinutes(5);
    private static final Duration BREAKPOINT_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redisTemplate;

    public void markInterrupted(String clientMessageId) {
        if (clientMessageId == null || clientMessageId.isBlank()) {
            return;
        }
        redisTemplate.opsForValue().set(INTERRUPT_PREFIX + clientMessageId, "1", INTERRUPT_TTL);
    }

    public boolean isInterrupted(String clientMessageId) {
        if (clientMessageId == null || clientMessageId.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(INTERRUPT_PREFIX + clientMessageId));
    }

    public void clearInterrupt(String clientMessageId) {
        if (clientMessageId == null || clientMessageId.isBlank()) {
            return;
        }
        redisTemplate.delete(INTERRUPT_PREFIX + clientMessageId);
    }

    public void saveBreakpoint(String sessionId, String clientMessageId, Map<String, Object> snapshot) {
        if (sessionId == null || clientMessageId == null || snapshot == null) {
            return;
        }
        redisTemplate.opsForValue().set(
                breakpointKey(sessionId, clientMessageId),
                JsonUtils.toJson(snapshot),
                BREAKPOINT_TTL);
    }

    public Map<String, Object> loadBreakpoint(String sessionId, String clientMessageId) {
        if (sessionId == null || clientMessageId == null) {
            return Collections.emptyMap();
        }
        String json = redisTemplate.opsForValue().get(breakpointKey(sessionId, clientMessageId));
        if (json == null || json.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Object> map = JsonUtils.fromJson(
                    json, new TypeReference<Map<String, Object>>() {
                    });
            return map == null ? Collections.emptyMap() : map;
        } catch (Exception e) {
            log.warn("[ChatBreakpoint] 断点反序列化失败，按无断点处理: key={}", clientMessageId, e);
            return Collections.emptyMap();
        }
    }

    public void clearBreakpoint(String sessionId, String clientMessageId) {
        if (sessionId == null || clientMessageId == null) {
            return;
        }
        redisTemplate.delete(breakpointKey(sessionId, clientMessageId));
        clearInterrupt(clientMessageId);
    }

    /** 新轮次（同会话新消息）清理全部旧断点——旧任务重试按钮随之失效 */
    public void clearSessionBreakpoints(String sessionId) {
        if (sessionId == null) {
            return;
        }
        Set<String> keys = redisTemplate.keys(BREAKPOINT_PREFIX + sessionId + ":*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private static String breakpointKey(String sessionId, String clientMessageId) {
        return BREAKPOINT_PREFIX + sessionId + ":" + clientMessageId;
    }
}
