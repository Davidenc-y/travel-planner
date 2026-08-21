package com.travel.planning.service;

import com.travel.common.dto.ChatResponseDTO;
import com.travel.common.entity.ChatMessage;
import com.travel.common.entity.ChatSession;
import com.travel.common.exception.BusinessException;
import com.travel.planning.memory.chat.ChatIntent;
import com.travel.planning.memory.sessionstore.SessionStorePort;
import com.travel.planning.memory.pipeline.ChatGuardStep;
import com.travel.planning.memory.pipeline.ChatPersistenceStep;
import com.travel.planning.memory.pipeline.ChatPreferenceStep;
import com.travel.planning.memory.pipeline.ChatKnowledgeStep;
import com.travel.planning.memory.pipeline.ChatIntentStep;
import com.travel.planning.memory.pipeline.ChatMemoryStep;
import com.travel.planning.memory.pipeline.ChatBudgetStep;
import com.travel.planning.memory.pipeline.ChatRoutingStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

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

    // F67/B3-1：会话/消息持久化收口到 SessionStorePort，业务不再直连 Mapper
    private final SessionStorePort sessionStorePort;
    // F85 第二步：入口意图分类（PLANNING/REFINE/RECALL/PROFILE/CHAT/FUNCTIONAL）
    // M3-10：步骤 1 安全防护收敛到 ChatGuardStep（MessagePipeline 切片）
    private final ChatGuardStep chatGuardStep;
    // M3-11：步骤 2 持久化（会话校验 + 用户消息落库）收敛到 ChatPersistenceStep
    private final ChatPersistenceStep chatPersistenceStep;
    // M3-12：步骤 3 偏好（确定性偏好保存）收敛到 ChatPreferenceStep
    private final ChatPreferenceStep chatPreferenceStep;
    // M3-13：步骤 4 知识（切片+异步写入）收敛到 ChatKnowledgeStep
    private final ChatKnowledgeStep chatKnowledgeStep;
    // M3-14：步骤 5 意图（分类+追溯填充）收敛到 ChatIntentStep
    private final ChatIntentStep chatIntentStep;
    // M3-15：步骤 6 记忆（画像+历史/摘要组装）收敛到 ChatMemoryStep
    private final ChatMemoryStep chatMemoryStep;
    // M3-16：步骤 7 预算（检索注入+组装+四档预算兜底）收敛到 ChatBudgetStep
    private final ChatBudgetStep chatBudgetStep;
    // M3-17：步骤 8 路由（意图分派 recall/direct/supervisor）收敛到 ChatRoutingStep
    private final ChatRoutingStep chatRoutingStep;

    /**
     * 创建会话
     */
    public String createSession(Long userId, String title) {
        return sessionStorePort.createSession(userId, title);
    }

    /**
     * 获取会话历史
     */
    public List<ChatMessage> getHistory(String sessionId) {
        return sessionStorePort.listMessages(sessionId);
    }

    /**
     * 发送消息并获取响应
     */
    public ChatResponseDTO sendMessage(String sessionId, String message, Long userId) {
        // F52：防御脏 userId（兜底 0 会导致 user_id=0 画像/会话）。
        if (userId == null || userId <= 0) {
            throw new BusinessException(40101, "用户未登录");
        }
        // F90：调用前安全防护（Prompt 注入检测）→ MessagePipeline 步骤 1
        chatGuardStep.check(userId, message);
        // M3-11：步骤 2 持久化（会话校验 + 用户消息落库）
        ChatSession session = chatPersistenceStep.requireSession(sessionId);
        chatPersistenceStep.appendUserMessage(sessionId, message);
        // M3-12：步骤 3 偏好（确定性偏好保存；语义同 F71）
        chatPreferenceStep.saveIfPreference(userId, message);
        // M3-13：步骤 4 知识（切片+异步写入；语义同 Phase C/F78 C1）
        chatKnowledgeStep.writeUserMessageAsync(sessionId, message);
        // M3-14：步骤 5 意图（分类+追溯填充；语义同 F85/F89）
        ChatIntent intent = chatIntentStep.classify(sessionId, userId, message);

        // M3-15：步骤 6 记忆（画像+历史/摘要组装；语义同 F50/F55/F57/F60）
        ChatMemoryStep.MemoryContext memory = chatMemoryStep.assemble(userId, sessionId);
        String profileContext = memory.profileContext();
        String historySection = memory.historySection();
        boolean summaryUsed = memory.summaryUsed();
        boolean summaryTriggered = memory.summaryTriggered();
        int turns = memory.turns();
        int totalHistoryTokens = memory.totalHistoryTokens();
        // M3-16：步骤 7 预算（检索注入+组装+四档预算兜底；语义同 F63/F66/F78/F83/F85）
        ChatBudgetStep.BudgetContext budget = chatBudgetStep.compose(sessionId, userId, intent,
                message, profileContext, historySection);
        String composed = budget.composed();
        int inputTokens = budget.inputTokens();
        profileContext = budget.profileContext();
        historySection = budget.historySection();
        String candidates = budget.candidates();
        List<Map<String, Object>> sessionHits = budget.sessionHits();

        log.info("聊天输入组装完成: 总长度={}, 含画像={}, 含历史={}, 含摘要={}, 摘要触发={}, 历史轮数={}, 全量历史token={}, 注入token={}, 含知识库候选={}",
                composed.length(), !profileContext.isBlank(), !historySection.isBlank(),
                summaryUsed, summaryTriggered, turns, totalHistoryTokens, inputTokens,
                !"[]".equals(candidates));

        // M3-17：步骤 8 路由（意图分派 recall/direct/supervisor；语义同 F85/F64/F27）
        ChatRoutingStep.RouteResult routed = chatRoutingStep.route(intent, composed, userId, sessionHits);
        String response = routed.response();
        long aiTokens = routed.aiTokens();

        // M3-18：步骤 9 落库（AI 响应保存；语义同 F27）
        chatPersistenceStep.appendAssistantMessage(sessionId, response, aiTokens);

        return ChatResponseDTO.builder()
                .sessionId(sessionId)
                .response(response)
                .tokens((int) aiTokens)
                .build();
    }

    /**
     * 获取用户活跃会话列表
     */
    public List<ChatSession> listSessions(Long userId) {
        return sessionStorePort.listActiveByUserId(userId);
    }
}
