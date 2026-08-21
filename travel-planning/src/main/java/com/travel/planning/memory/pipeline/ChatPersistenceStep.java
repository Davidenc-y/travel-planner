package com.travel.planning.memory.pipeline;

import com.travel.common.entity.ChatSession;
import com.travel.common.enums.ChatRole;
import com.travel.common.exception.BusinessException;
import com.travel.common.util.TextTokens;
import com.travel.planning.memory.sessionstore.SessionStorePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * M3-11/M3-18：MessagePipeline 步骤 2「持久化」+ 步骤 9「落库」。
 * 会话校验 + 用户消息落库 + AI 响应落库从 ChatService 抽出为独立可测步骤。
 */
@Component
@RequiredArgsConstructor
public class ChatPersistenceStep {

    private final SessionStorePort sessionStorePort;

    /**
     * 校验会话存在；不存在抛 40404（语义与 ChatService 原实现一致）。
     */
    public ChatSession requireSession(String sessionId) {
        ChatSession session = sessionStorePort.findBySessionId(sessionId);
        if (session == null) {
            throw new BusinessException(40404, "会话不存在: " + sessionId);
        }
        return session;
    }

    /**
     * 保存用户消息；tokens 采用 TextTokens 启发式估算（F27 口径，服务端无 tokenizer）。
     */
    public void appendUserMessage(String sessionId, String message) {
        sessionStorePort.appendMessage(sessionId, ChatRole.USER, message, TextTokens.estimate(message));
    }

    /**
     * 保存 AI 响应；tokens 为本次全部 LLM 调用的真实 totalTokens（F27 口径）。
     */
    public void appendAssistantMessage(String sessionId, String response, long aiTokens) {
        sessionStorePort.appendMessage(sessionId, ChatRole.ASSISTANT, response, (int) aiTokens);
    }
}
