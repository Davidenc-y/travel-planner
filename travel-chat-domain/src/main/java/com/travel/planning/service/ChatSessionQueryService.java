package com.travel.planning.service;

import com.travel.common.entity.ChatSession;
import com.travel.common.enums.SessionStatus;
import com.travel.common.exception.BusinessException;
import com.travel.common.exception.ErrorCode;
import com.travel.memory.sessionstore.SessionStorePort;
import com.travel.memory.shortterm.SessionFinalizer;
import com.travel.planning.memory.pipeline.ChatPersistenceStep;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Z-4b：会话查询/生命周期服务（自 ChatService 拆出——建会话/历史/关会话）。
 *
 * <p>E-4 式逐字迁移：方法体与 {@code requireOwnedSession} 守卫自 ChatService 原文搬运
 * （Z-4a 同款副本——被迁方法仅作所有权校验用）；结果 record
 * {@link ChatService.CloseSessionResult} 留守 ChatService（公共 API FQN 零变——
 * ChatController 签名以 FQN 引用）；ChatService 保留同名委托方法。</p>
 */
@Service
@RequiredArgsConstructor
public class ChatSessionQueryService {

    private final SessionStorePort sessionStorePort;
    private final ChatPersistenceStep chatPersistenceStep;
    private final SessionFinalizer sessionFinalizer;

    /**
     * 创建会话
     */
    public String createSession(Long userId, String title) {
        return sessionStorePort.createSession(userId, title);
    }

    /**
     * 获取会话历史
     */
    /** M21-2 SEC-02-03 止血：历史读取前先校验会话归属（40404 不存在 / 40302 非本人）。 */
    public java.util.List<com.travel.common.entity.ChatMessage> getHistory(Long userId, String sessionId) {
        requireOwnedSession(userId, sessionId);
        return sessionStorePort.listMessages(sessionId);
    }

    /**
     * M4-4/P1-1：关闭会话（显式触发；禁止前端 beforeunload 调用——刷新会误归档）。
     *
     * <p>幂等：已 ARCHIVED 直接返回；条件更新 ACTIVE→ARCHIVED 防并发双关；
     * 归档后同步尽力收口（超时/失败转隐式待办，启动补偿/空闲扫描兜底）。
     * history 查询不受归档影响（只读）。</p>
     */
    public ChatService.CloseSessionResult closeSession(Long userId, String sessionId) {
        ChatSession session = requireOwnedSession(userId, sessionId);
        if (SessionStatus.ARCHIVED.name().equals(session.getStatus())) {
            return new ChatService.CloseSessionResult(true, session.getSummaryFinal() != null);
        }
        int updated = sessionStorePort.updateStatus(
                sessionId, SessionStatus.ACTIVE.name(), SessionStatus.ARCHIVED.name());
        if (updated == 0) {
            // 并发 close：重读判定幂等语义
            ChatSession fresh = sessionStorePort.findBySessionId(sessionId);
            if (fresh != null
                    && SessionStatus.ARCHIVED.name().equals(fresh.getStatus())) {
                return new ChatService.CloseSessionResult(true, fresh.getSummaryFinal() != null);
            }
            throw new BusinessException(ErrorCode.SESSION_STATE_CONFLICT.code(),
                    ErrorCode.SESSION_STATE_CONFLICT.message());
        }
        boolean finalized = sessionFinalizer.finalizeSession(sessionId);
        return new ChatService.CloseSessionResult(true, finalized);
    }

    private ChatSession requireOwnedSession(Long userId, String sessionId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED.code(), ErrorCode.UNAUTHORIZED.message());
        }
        ChatSession session = chatPersistenceStep.requireSession(sessionId);
        if (!userId.equals(session.getUserId())) {
            throw new BusinessException(ErrorCode.SESSION_ACCESS_DENIED.code(),
                    ErrorCode.SESSION_ACCESS_DENIED.message());
        }
        return session;
    }
}
