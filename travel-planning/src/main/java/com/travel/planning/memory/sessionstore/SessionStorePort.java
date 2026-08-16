package com.travel.planning.memory.sessionstore;

import com.travel.common.entity.ChatMessage;
import com.travel.common.entity.ChatSession;
import com.travel.common.enums.ChatRole;

import java.util.List;

/**
 * 会话/消息持久化端口（F67/B3-1）。
 *
 * <p>把 t_chat_session / t_chat_message 的读写收口到 Port，业务层（ChatService、
 * SessionMemoryServiceImpl 等）不再直连 Mapper，满足 F48 9.2"接口先行、依赖倒置、
 * 数据所有权唯一"原则；未来拆分 travel-memory 模块时仅迁移实现。</p>
 */
public interface SessionStorePort {

    /**
     * 创建会话（生成 sessionId、标题兜底、状态 ACTIVE），返回 sessionId。
     */
    String createSession(Long userId, String title);

    /**
     * 按 sessionId 查询会话；不存在返回 null。
     */
    ChatSession findBySessionId(String sessionId);

    /**
     * 查询用户活跃会话列表（按创建时间倒序）。
     */
    List<ChatSession> listActiveByUserId(Long userId);

    /**
     * 加载会话全部消息（按 created_at 升序）。
     */
    List<ChatMessage> listMessages(String sessionId);

    /**
     * 追加一条消息（append-only；role 来自 {@link ChatRole}）。
     */
    void appendMessage(String sessionId, ChatRole role, String content, Integer tokens);
}
