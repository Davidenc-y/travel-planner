package com.travel.planning.memory.pipeline;

import com.travel.planning.memory.chat.ChatIntent;
import com.travel.planning.memory.knowledge.KnowledgeRetrievalService;
import com.travel.planning.memory.knowledge.SessionFactConsolidator;
import com.travel.planning.memory.knowledge.SessionKnowledgeWriter;
import com.travel.planning.memory.shortterm.ContextComposer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * M3-16：MessagePipeline 步骤 7「预算」。
 * 检索注入（候选/会话知识/共识）+ 上下文组装与四档预算兜底从 ChatService 抽出为独立可测步骤。
 */
@Component
@RequiredArgsConstructor
public class ChatBudgetStep {

    /**
     * 组装结果：注入文本、token 数、画像/历史段（可能被预算兜底收紧）、候选与会话命中
     * （会话命中供步骤 8 路由 RECALL 消费）。
     */
    public record BudgetContext(String composed, int inputTokens,
                                String profileContext, String historySection,
                                String candidates, List<Map<String, Object>> sessionHits) {
    }

    private final KnowledgeRetrievalService knowledgeRetrievalService;
    private final SessionKnowledgeWriter sessionKnowledgeWriter;
    private final SessionFactConsolidator sessionFactConsolidator;
    private final ContextComposer contextComposer;

    /**
     * 组装 画像+历史+共识+会话知识+候选+当前问题，并执行四档 token 预算兜底
     * （F63/F66 预检索门控、F78 C3 会话知识检索、F83 topK=8、F85 共识、M3-9 兜底）。
     */
    public BudgetContext compose(String sessionId, Long userId, ChatIntent intent,
                                 String message, String profileContext, String historySection) {
        // F63：确定性预检索注入——把知识库候选景点放入上下文，确保聊天链消费知识库。
        // F66：非检索意图（画像/偏好/闲聊类）跳过预检索，避免无关候选污染上下文。
        String candidates = needsKnowledgeRetrievalByIntent(intent)
                ? knowledgeRetrievalService.retrieveCandidates(message, 5) : "[]";
        // Phase C/F78（C3）：按需检索本会话历史知识（结构化，供共识层与注入共用，只检索一次）
        // F83：topK 放大到 8，避免类型加分把行程切片挤出注入（E4 召回问题）
        List<Map<String, Object>> sessionHits = sessionKnowledgeWriter.searchStructured(sessionId, message, 8);
        String sessionContext = SessionKnowledgeWriter.format(sessionHits);
        // F85：会话事实共识——同主题 feedback 覆盖旧 constraint，注入【会话最新确认】
        String consensus = sessionFactConsolidator.render(sessionFactConsolidator.consolidate(sessionHits));
        // M3-9：上下文组装与四档预算兜底收敛到 ContextComposer（行为等价）
        ContextComposer.ComposedContext cc = contextComposer.compose(sessionId, userId,
                profileContext, historySection, consensus, sessionContext, candidates, message);
        return new BudgetContext(cc.text(), cc.tokens(), cc.profileContext(), cc.historySection(),
                candidates, sessionHits);
    }

    /**
     * F85：意图驱动的知识预检索门控（取代 F66 独立关键词表，避免两套启发式漂移）。
     * PROFILE/CHAT/FUNCTIONAL → 跳过预检索；PLANNING/REFINE/RECALL → 开启。
     */
    private static boolean needsKnowledgeRetrievalByIntent(ChatIntent intent) {
        return intent == ChatIntent.PLANNING || intent == ChatIntent.REFINE || intent == ChatIntent.RECALL;
    }
}
