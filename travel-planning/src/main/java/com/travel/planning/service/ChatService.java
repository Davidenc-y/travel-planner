package com.travel.planning.service;

import com.travel.common.dto.ChatResponseDTO;
import com.travel.common.entity.ChatMessage;
import com.travel.common.entity.ChatSession;
import com.travel.common.enums.ChatRole;
import com.travel.common.exception.BusinessException;
import com.travel.planning.agent.supervisor.TravelSupervisorAgent;
import com.travel.planning.repository.ChatMessageMapper;
import com.travel.planning.repository.ChatSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 聊天服务
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final TravelSupervisorAgent supervisorAgent;

    /**
     * 创建会话
     */
    public String createSession(Long userId, String title) {
        String sessionId = UUID.randomUUID().toString();
        ChatSession session = new ChatSession();
        session.setSessionId(sessionId);
        session.setUserId(userId);
        session.setTitle(title != null ? title : "旅游规划对话");
        session.setStatus("ACTIVE");
        sessionMapper.insert(session);
        log.info("创建聊天会话: sessionId={}, userId={}", sessionId, userId);
        return sessionId;
    }

    /**
     * 获取会话历史
     */
    public List<ChatMessage> getHistory(String sessionId) {
        return messageMapper.findBySessionId(sessionId);
    }

    /**
     * 发送消息并获取响应
     */
    public ChatResponseDTO sendMessage(String sessionId, String message, Long userId) {
        // 1. 校验会话
        ChatSession session = sessionMapper.findBySessionId(sessionId);
        if (session == null) {
            throw new BusinessException(40404, "会话不存在: " + sessionId);
        }

        // 2. 保存用户消息
        ChatMessage userMsg = new ChatMessage();
        userMsg.setSessionId(sessionId);
        userMsg.setRole(ChatRole.USER.name().toLowerCase());
        userMsg.setContent(message);
        messageMapper.insert(userMsg);

        // 3. 调用 SupervisorAgent
        String response;
        try {
            response = supervisorAgent.executePlanning(message);
        } catch (Exception e) {
            log.error("Agent 调用失败", e);
            response = "抱歉，处理您的请求时出现错误，请稍后重试。";
        }

        // 4. 保存 AI 响应
        ChatMessage aiMsg = new ChatMessage();
        aiMsg.setSessionId(sessionId);
        aiMsg.setRole(ChatRole.ASSISTANT.name().toLowerCase());
        aiMsg.setContent(response);
        messageMapper.insert(aiMsg);

        return ChatResponseDTO.builder()
                .sessionId(sessionId)
                .response(response)
                .build();
    }

    /**
     * 获取用户活跃会话列表
     */
    public List<ChatSession> listSessions(Long userId) {
        return sessionMapper.findActiveByUserId(userId);
    }
}
