package com.travel.planning.memory.sessionstore;

import com.travel.common.entity.ChatMessage;
import com.travel.common.entity.ChatSession;
import com.travel.common.enums.ChatRole;
import com.travel.planning.repository.ChatMessageMapper;
import com.travel.planning.repository.ChatSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
@RequiredArgsConstructor
public class SessionStoreServiceImpl implements SessionStorePort {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;

    @Override
    public String createSession(Long userId, String title) {
        ChatSession session = new ChatSession();
        session.setSessionId(UUID.randomUUID().toString());
        session.setUserId(userId);
        session.setTitle(title != null ? title : DEFAULT_SESSION_TITLE);
        session.setStatus("ACTIVE");
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
        return sessionMapper.findActiveByUserId(userId);
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
        return sessionMapper.updateStatusConditional(sessionId, from, to);
    }

    @Override
    public int updateSummaryFinal(String sessionId, String text) {
        return sessionMapper.updateSummaryFinal(sessionId, text);
    }

    @Override
    public int updateTitle(String sessionId, String title) {
        return sessionMapper.updateTitle(sessionId, title);
    }

    @Override
    public int updateTitleIfDefault(String sessionId, String title, String defaultTitle) {
        return sessionMapper.updateTitleIfDefault(sessionId, title, defaultTitle);
    }

    @Override
    public List<ChatSession> findArchivedWithoutFinal(java.time.LocalDateTime updatedBefore, int limit) {
        return sessionMapper.findArchivedWithoutFinal(updatedBefore, limit);
    }
}
