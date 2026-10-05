package com.travel.planning.memory.sessionstore;

import com.travel.common.cache.RedisResultCache;
import com.travel.common.entity.ChatMessage;
import com.travel.common.entity.ChatSession;
import com.travel.common.enums.ChatRole;
import com.travel.common.enums.SessionStatus;
import com.travel.common.util.JsonUtils;
import com.travel.planning.repository.ChatMessageMapper;
import com.travel.memory.repository.ChatSessionMapper;
import com.travel.memory.sessionstore.SessionStorePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 会话/消息持久化实现（F67/B3-1）。
 *
 * <p>唯一直连 t_chat_session / t_chat_message Mapper 的类；业务层经由
 * {@link SessionStorePort} 访问，保持可拆解性（镜像 travel-common 实体与 repository）。</p>
 */
@Slf4j
@Service
public class SessionStoreServiceImpl implements SessionStorePort {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    /** BB-3：会话列表 Redis 结果缓存（key travel:chat:sessions:{userId}，30s TTL+抖动，fail-open） */
    private final RedisResultCache sessionCache;

    public SessionStoreServiceImpl(ChatSessionMapper sessionMapper,
                                   ChatMessageMapper messageMapper,
                                   StringRedisTemplate redisTemplate) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.sessionCache = new RedisResultCache(redisTemplate, "travel:chat:sessions:");
    }

    @Override
    public String createSession(Long userId, String title) {
        ChatSession session = new ChatSession();
        session.setSessionId(UUID.randomUUID().toString());
        session.setUserId(userId);
        session.setTitle(title != null ? title : DEFAULT_SESSION_TITLE);
        session.setStatus(SessionStatus.ACTIVE.name());
        sessionMapper.insert(session);
        log.info("创建聊天会话: sessionId={}, userId={}", session.getSessionId(), userId);
        return session.getSessionId();
    }

    @Override
    public ChatSession findBySessionId(String sessionId) {
        return sessionMapper.findBySessionId(sessionId);
    }

    @Override
    public List<ChatSession> listActiveByUserId(Long userId) {
        // BB-3：30s TTL 结果缓存。值以 JSON 字符串承载（经 JsonUtils=JavaTimeModule；ChatSession
        // 含 LocalDateTime，冻结骨架 RedisResultCache 的裸 Mapper 无法序列化 java.time——见审计
        // 日志 R3 裁决②），出参 typed 反序列化；fail-open 全程降级直查。
        String json = sessionCache.computeIfAbsent(String.valueOf(userId), String.class,
                Duration.ofSeconds(30),
                () -> JsonUtils.toJson(sessionMapper.findActiveByUserId(userId)));
        if (json == null || json.isBlank()) {
            return sessionMapper.findActiveByUserId(userId);
        }
        try {
            ChatSession[] sessions = JsonUtils.fromJson(json, ChatSession[].class);
            return Arrays.asList(sessions == null ? new ChatSession[0] : sessions);
        } catch (Exception e) {
            log.debug("session list 缓存反序列化降级直查: userId={}", userId);
            return sessionMapper.findActiveByUserId(userId);
        }
    }

    @Override
    public List<ChatMessage> listMessages(String sessionId) {
        return messageMapper.findBySessionId(sessionId);
    }

    @Override
    public Long appendMessage(String sessionId, ChatRole role, String content, Integer tokens) {
        ChatMessage msg = new ChatMessage();
        msg.setSessionId(sessionId);
        msg.setRole(role.name().toLowerCase());
        msg.setContent(content);
        msg.setTokens(tokens);
        messageMapper.insert(msg);
        // M4-3：MyBatis-Plus 插入后回填自增 id，幂等登记需要
        return msg.getId();
    }

    @Override
    public ChatMessage findMessageById(Long id) {
        return id == null ? null : messageMapper.selectById(id);
    }

    @Override
    public int updateStatus(String sessionId, String from, String to) {
        int updated = sessionMapper.updateStatusConditional(sessionId, from, to);
        // BB-3：状态变更（含 ACTIVE→ARCHIVED=关会话语义写点）失效列表缓存；失效-only 安全
        evictUserSessionList(sessionId);
        return updated;
    }

    @Override
    public int updateSummaryFinal(String sessionId, String text) {
        return sessionMapper.updateSummaryFinal(sessionId, text);
    }

    @Override
    public int updateTitle(String sessionId, String title) {
        int updated = sessionMapper.updateTitle(sessionId, title);
        // BB-3：标题改名=语义写点→失效列表缓存（追加不替换，P0-⑷）
        evictUserSessionList(sessionId);
        return updated;
    }

    @Override
    public int updateTitleIfDefault(String sessionId, String title, String defaultTitle) {
        int updated = sessionMapper.updateTitleIfDefault(sessionId, title, defaultTitle);
        // BB-3：首条消息自动改名=一次性语义变更→失效列表缓存（appendMessage 明确不 evict）
        evictUserSessionList(sessionId);
        return updated;
    }

    /** BB-3：语义写点→按 sessionId 回查 userId 失效列表缓存（改名/关会话低频，点查开销可忽略；fail-open）。 */
    private void evictUserSessionList(String sessionId) {
        try {
            ChatSession session = sessionMapper.findBySessionId(sessionId);
            if (session != null && session.getUserId() != null) {
                sessionCache.evict(String.valueOf(session.getUserId()));
            }
        } catch (Exception e) {
            log.debug("session list evict 跳过（fail-open）: sessionId={}", sessionId);
        }
    }

    @Override
    public List<ChatSession> findArchivedWithoutFinal(java.time.LocalDateTime updatedBefore, int limit) {
        return sessionMapper.findArchivedWithoutFinal(updatedBefore, limit);
    }
}
