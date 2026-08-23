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
     *
     * @return 自增消息 id（M4-3：幂等登记需回填 user/assistant 消息 id）
     */
    Long appendMessage(String sessionId, ChatRole role, String content, Integer tokens);

    /**
     * 按消息 id 查询；不存在返回 null（M4-3：幂等重放时取 assistant 消息内容）。
     */
    ChatMessage findMessageById(Long id);

    /**
     * M4-4：条件状态迁移（from 不匹配返回 0 行——并发/重复 close 防护）。
     *
     * @return 实际更新行数（0=状态已被并发迁移）
     */
    int updateStatus(String sessionId, String from, String to);

    /**
     * M4-4：收口摘要持久化（幂等首写：summary_final IS NULL 才更新）。
     *
     * @return 实际更新行数
     */
    int updateSummaryFinal(String sessionId, String text);

    /**
     * M4-4：启动补偿扫描——已归档但收口未完成的会话（updatedBefore 排除刚 close 在途）。
     */
    List<ChatSession> findArchivedWithoutFinal(java.time.LocalDateTime updatedBefore, int limit);
}
