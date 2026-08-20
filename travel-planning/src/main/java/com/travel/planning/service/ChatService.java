package com.travel.planning.service;

import com.travel.common.dto.ChatResponseDTO;
import com.travel.common.entity.ChatMessage;
import com.travel.common.entity.ChatSession;
import com.travel.common.enums.ChatRole;
import com.travel.common.exception.BusinessException;
import com.travel.planning.agent.supervisor.TravelSupervisorAgent;
import com.travel.planning.memory.longterm.ProfileContextAssembler;
import com.travel.planning.memory.longterm.ProfilePort;
import com.travel.planning.memory.longterm.PreferenceSaveService;
import com.travel.planning.memory.knowledge.KnowledgeRetrievalService;
import com.travel.planning.memory.knowledge.SessionContextChunker;
import com.travel.planning.memory.knowledge.SessionFactConsolidator;
import com.travel.planning.memory.knowledge.SessionKnowledgeWriter;
import com.travel.planning.memory.chat.ChatIntent;
import com.travel.planning.memory.chat.ChatIntentClassifier;
import com.travel.planning.memory.chat.ChatIntentProperties;
import com.travel.planning.guard.GuardService;
import com.travel.planning.trace.TraceContext;
import com.travel.planning.memory.sessionstore.SessionStorePort;
import com.travel.planning.memory.shortterm.ShortTermMemoryProperties;
import com.travel.planning.memory.shortterm.SessionMemoryPort;
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
    private final TravelSupervisorAgent supervisorAgent;
    private final SessionMemoryPort sessionMemoryPort;
    private final ProfilePort profilePort;
    private final ProfileContextAssembler profileContextAssembler;
    private final ShortTermMemoryProperties memoryProps;
    private final KnowledgeRetrievalService knowledgeRetrievalService;
    // F71：偏好陈述消息的确定性保存（不依赖 Agent 工具调用）
    private final PreferenceSaveService preferenceSaveService;
    // Phase C/F78：会话级知识切片写入与检索注入
    private final SessionContextChunker sessionContextChunker;
    private final SessionKnowledgeWriter sessionKnowledgeWriter;
    // F85：会话事实共识（同主题 feedback 覆盖旧 constraint，注入【会话最新确认】）
    private final SessionFactConsolidator sessionFactConsolidator;
    // F85 第二步：入口意图分类（PLANNING/REFINE/RECALL/PROFILE/CHAT/FUNCTIONAL）
    private final ChatIntentClassifier chatIntentClassifier;
    private final ChatIntentProperties chatIntentProperties;
    // F90：Agent 调用前安全防护（Prompt 注入等）
    private final GuardService guardService;

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
        // F90：调用前安全防护（Prompt 注入检测）
        var guard = guardService.check(String.valueOf(userId), message);
        if (!guard.allowed()) {
            throw new BusinessException(40302, guard.reason());
        }
        // 1. 校验会话
        ChatSession session = sessionStorePort.findBySessionId(sessionId);
        if (session == null) {
            throw new BusinessException(40404, "会话不存在: " + sessionId);
        }

        // 2. 保存用户消息
        // F27：user 消息 tokens = 输入估算值（服务端无 tokenizer，启发式估算，见方法注释）
        sessionStorePort.appendMessage(sessionId, ChatRole.USER, message, estimateInputTokens(message));
        // F71：偏好陈述消息（"记住我喜欢爬山，预算8000元"）确定性保存到画像，
        // 保证用户明确表达的偏好不因 LLM 跳过工具而丢失（合并语义见 ProfilePort.update）。
        preferenceSaveService.saveIfPreferenceStatement(userId, message);
        // Phase C/F78（C1）：约束/反馈类消息立即异步写入会话知识（幂等，失败不影响主流程）
        sessionKnowledgeWriter.writeAsync(sessionId,
                sessionContextChunker.chunkUserMessage(sessionId, message));
        // F85 第二步：入口意图分类（开关关闭时回退 PLANNING）
        ChatIntent intent = chatIntentClassifier.classify(message);
        // F89：追溯上下文填充（user/session/意图路径）
        if (TraceContext.active()) {
            TraceContext.Holder h = TraceContext.current();
            h.trace.setUserId(userId);
            h.trace.setSessionId(sessionId);
            h.addPath(intent.name().toLowerCase());
        }

        // 3. F50/Phase A + F55/B1：组合 画像 + (摘要+滑动窗口 | 原文历史) + 当前问题
        //    （原始消息存储不变；组合仅用于本次推理输入）。
        String profileContext = profileContextAssembler.assemble(profilePort.getOrCreate(userId));
        String rawHistory = sessionMemoryPort.composeHistoryContext(sessionId, memoryProps.getMaxTurns());
        int turns = sessionMemoryPort.countUserTurns(sessionId);
        // F57：以全量汇总 token 作为触发依据（截断前统计），配合轮数触发。
        int totalHistoryTokens = sessionMemoryPort.totalHistoryTokens(sessionId);
        String historySection;
        boolean summaryUsed = false;
        boolean summaryTriggered = false;
        // F57：触发条件 = 轮数 ≥ summaryMinTurns 或 全量汇总 token ≥ 预算×ratio。
        if (memoryProps.isEnabled() && !rawHistory.isBlank()
                && (turns >= memoryProps.getSummaryMinTurns()
                    || totalHistoryTokens >= (int) (memoryProps.getHistoryMaxTokens() * memoryProps.getSummaryThresholdRatio()))) {
            summaryTriggered = true;
            // F60：触发即异步滚动——doSummarize 内部按 summaryRefreshTurns 决定
            // 真正重生成或仅续期 TTL；否则摘要生成一次后永不刷新（死代码缺陷）。
            sessionMemoryPort.summarizeAsync(sessionId);
            String summary = sessionMemoryPort.getSummaryOrEmpty(sessionId);
            if (summary.isBlank()) {
                // 摘要尚未生成（首轮触发）：本轮仍用原文窗口。
                historySection = rawHistory;
            } else {
                StringBuilder sb = new StringBuilder("【会话摘要】\n").append(summary);
                String recent = sessionMemoryPort.composeRecentWindow(sessionId, memoryProps.getRecentWindowTurns());
                if (!recent.isBlank()) {
                    sb.append("\n\n【最近对话】\n").append(recent);
                }
                historySection = sb.toString();
                summaryUsed = true;
            }
        } else {
            historySection = rawHistory;
        }
        // F63：确定性预检索注入——把知识库候选景点放入上下文，确保聊天链消费知识库。
        // F66：非检索意图（画像/偏好/闲聊类）跳过预检索，避免无关候选污染上下文
        // （20:18 实测"我的旅行画像里有什么？"被注入 5 条无关景点候选）。
        String candidates = needsKnowledgeRetrievalByIntent(intent)
                ? knowledgeRetrievalService.retrieveCandidates(message, 5) : "[]";
        // Phase C/F78（C3）：按需检索本会话历史知识（结构化，供共识层与注入共用，只检索一次）
        // F83：topK 放大到 8，避免类型加分把行程切片挤出注入（E4 召回问题）
        List<Map<String, Object>> sessionHits = sessionKnowledgeWriter.searchStructured(sessionId, message, 8);
        String sessionContext = SessionKnowledgeWriter.format(sessionHits);
        // F85：会话事实共识——同主题 feedback 覆盖旧 constraint，注入【会话最新确认】
        String consensus = sessionFactConsolidator.render(sessionFactConsolidator.consolidate(sessionHits));
        String composed = composeInput(profileContext, historySection, consensus, sessionContext, candidates, message);
        int inputTokens = sessionMemoryPort.estimateTokens(composed);

        // F58/B1.2：注入总预算兜底（超限先去掉最近窗口，仍超则压缩摘要）。
        if (inputTokens > memoryProps.getInputMaxTokens()) {
            // M3-2/P2-11：不再依赖 summaryUsed 才压缩——只要存在摘要即尝试（无摘要也能走该路径）
            String summaryOnly = sessionMemoryPort.getSummaryOrEmpty(sessionId);
            boolean hasSummary = !summaryOnly.isBlank();
            if (hasSummary) {
                historySection = "【会话摘要】\n" + summaryOnly;
                composed = composeInput(profileContext, historySection, consensus, sessionContext, candidates, message);
                inputTokens = sessionMemoryPort.estimateTokens(composed);
            }
            if (inputTokens > memoryProps.getInputMaxTokens() && hasSummary) {
                int reserve = sessionMemoryPort.estimateTokens(profileContext)
                        + sessionMemoryPort.estimateTokens("【当前问题】\n" + message) + 8;
                String cut = sessionMemoryPort.truncateByTokens(
                        summaryOnly, Math.max(100, memoryProps.getInputMaxTokens() - reserve));
                historySection = "【会话摘要】\n" + cut;
                composed = composeInput(profileContext, historySection, consensus, sessionContext, candidates, message);
                inputTokens = sessionMemoryPort.estimateTokens(composed);
                log.warn("[ChatService] 注入总预算超限，已压缩摘要: tokens={}", inputTokens);
            } else if (inputTokens > memoryProps.getInputMaxTokens()) {
                log.warn("[ChatService] 注入总预算超限（无摘要可压缩）: tokens={}", inputTokens);
            }
            if (inputTokens > memoryProps.getInputMaxTokens()) {
                // B3-4/F72：注入总预算仍超限时，收紧画像段（默认 800 → 400 tokens），
                // 补齐 F65 4.3 指出的"兜底不覆盖画像段"盲区。
                profileContext = profileContextAssembler.assemble(
                        profilePort.getOrCreate(userId), memoryProps.getProfileMaxTokens() / 2);
                composed = composeInput(profileContext, historySection, consensus, sessionContext, candidates, message);
                inputTokens = sessionMemoryPort.estimateTokens(composed);
                log.warn("[ChatService] 注入总预算超限，已收紧画像段: tokens={}", inputTokens);
            }
        }

        log.info("聊天输入组装完成: 总长度={}, 含画像={}, 含历史={}, 含摘要={}, 摘要触发={}, 历史轮数={}, 全量历史token={}, 注入token={}, 含知识库候选={}",
                composed.length(), !profileContext.isBlank(), !historySection.isBlank(),
                summaryUsed, summaryTriggered, turns, totalHistoryTokens, inputTokens,
                !"[]".equals(candidates));

        String response;
        long aiTokens = 0;
        long routeStart = System.currentTimeMillis();
        try {
            switch (intent) {
                case RECALL -> {
                    // F85：轻量回顾管线（itinerary_day 骨架 + LLM 润色，零编造）
                    TravelSupervisorAgent.PlanningResult result =
                            supervisorAgent.answerRecall(composed, sessionHits);
                    response = result.answer();
                    aiTokens = result.totalTokens();
                }
                case PROFILE, CHAT, FUNCTIONAL -> {
                    // F85：入口直答（不触发 supervisor，覆盖优先级 system 指令）
                    TravelSupervisorAgent.PlanningResult result =
                            supervisorAgent.answerDirect(composed, userId);
                    response = result.answer();
                    aiTokens = result.totalTokens();
                }
                default -> { // PLANNING / REFINE：F64/B2 把 userId 传入 Supervisor（metadata 供画像工具）
                    TravelSupervisorAgent.PlanningResult result =
                            supervisorAgent.executePlanningWithUsage(composed, userId);
                    response = result.answer();
                    // F27：assistant 消息 tokens = 本次全部 LLM 调用的真实 totalTokens 之和
                    aiTokens = result.totalTokens();
                }
            }
        } catch (Exception e) {
            log.error("Agent 调用失败", e);
            response = "抱歉，处理您的请求时出现错误，请稍后重试。";
        }
        long routeElapsed = System.currentTimeMillis() - routeStart;
        log.info("[ChatRouting] intent={}, router={}, elapsedMs={}",
                intent, routerOf(intent), routeElapsed);

        // 4. 保存 AI 响应
        sessionStorePort.appendMessage(sessionId, ChatRole.ASSISTANT, response, (int) aiTokens);

        return ChatResponseDTO.builder()
                .sessionId(sessionId)
                .response(response)
                .tokens((int) aiTokens)
                .build();
    }

    /**
     * 组合 画像 + 历史/摘要段 + 当前问题
     */
    private String composeInput(String profileContext, String historySection, String consensus,
                                String sessionContext, String candidates, String message) {
        StringBuilder input = new StringBuilder();
        if (!profileContext.isBlank()) {
            input.append(profileContext).append("\n\n");
        }
        if (!historySection.isBlank()) {
            input.append(historySection).append("\n\n");
        }
        // F85：会话最新确认（确定性覆盖结论）优先于原始切片
        if (consensus != null && !consensus.isBlank()) {
            input.append(consensus).append("\n\n");
        }
        // Phase C/F78（C3）：会话历史知识参考段
        if (sessionContext != null && !sessionContext.isBlank()) {
            input.append("【会话知识参考】\n").append(sessionContext).append("\n\n");
        }
        if (candidates != null && !candidates.isBlank() && !"[]".equals(candidates)) {
            input.append("【知识库检索候选景点】\n").append(candidates).append("\n\n");
        }
        input.append("【当前问题】\n").append(message);
        return input.toString();
    }

    /**
     * F27：估算用户消息的输入 token 数。
     *
     * <p>服务端无 tokenizer，采用文档化启发式：中文（CJK）约 1 字符 ≈ 1 token，
     * 英文/数字约 4 字符 ≈ 1 token；最小返回 1。assistant 侧为真实用量。</p>
     */
    private static int estimateInputTokens(String text) {
        // M3-5：统一 token 估算
        return com.travel.common.util.TextTokens.estimate(text);
    }

    /**
     * F85：意图驱动的知识预检索门控（取代 F66 独立关键词表，避免两套启发式漂移）。
     * PROFILE/CHAT/FUNCTIONAL → 跳过预检索；PLANNING/REFINE/RECALL → 开启。
     */
    private static boolean needsKnowledgeRetrievalByIntent(ChatIntent intent) {
        return intent == ChatIntent.PLANNING || intent == ChatIntent.REFINE || intent == ChatIntent.RECALL;
    }

    private static String routerOf(ChatIntent intent) {
        return switch (intent) {
            case RECALL -> "recall";
            case PROFILE, CHAT, FUNCTIONAL -> "direct";
            default -> "supervisor";
        };
    }

    /**
     * 获取用户活跃会话列表
     */
    public List<ChatSession> listSessions(Long userId) {
        return sessionStorePort.listActiveByUserId(userId);
    }
}
