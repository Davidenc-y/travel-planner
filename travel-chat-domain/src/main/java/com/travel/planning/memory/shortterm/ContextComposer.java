package com.travel.planning.memory.shortterm;

import com.travel.planning.memory.longterm.ProfileContextAssembler;
import com.travel.planning.memory.longterm.ProfilePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * M3-9：对话上下文组合器（MessagePipeline 第二步）。
 * 承载「画像+历史/摘要+共识+会话知识+候选+当前问题」组装与四级 token 预算兜底，
 * 使 ChatService 职责收敛、组件可独立单测。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextComposer {

    private final SessionMemoryPort sessionMemoryPort;
    private final ProfilePort profilePort;
    private final ProfileContextAssembler profileContextAssembler;
    private final ShortTermMemoryProperties memoryProps;

    public record ComposedContext(String text, int tokens, String profileContext,
                                  String historySection) {
    }

    public ComposedContext compose(String sessionId, Long userId,
                                   String profileContext, String historySection,
                                   String consensus, String sessionContext,
                                   String candidates, String message) {
        ComposedInput ci = composeWithTokens(profileContext, historySection, consensus,
                sessionContext, candidates, message);
        String composed = ci.text();
        int inputTokens = ci.tokens();

        if (inputTokens > memoryProps.getInputMaxTokens()) {
            String summaryOnly = sessionMemoryPort.getSummaryOrEmpty(sessionId);
            boolean hasSummary = !summaryOnly.isBlank();
            if (hasSummary) {
                historySection = "【会话摘要】\n" + summaryOnly;
                ComposedInput c1 = composeWithTokens(profileContext, historySection, consensus,
                        sessionContext, candidates, message);
                composed = c1.text();
                inputTokens = c1.tokens();
            }
            if (inputTokens > memoryProps.getInputMaxTokens() && hasSummary) {
                int reserve = sessionMemoryPort.estimateTokens(profileContext)
                        + sessionMemoryPort.estimateTokens("【当前问题】\n" + message) + 8;
                String cut = sessionMemoryPort.truncateByTokens(
                        summaryOnly, Math.max(100, memoryProps.getInputMaxTokens() - reserve));
                historySection = "【会话摘要】\n" + cut;
                ComposedInput c2 = composeWithTokens(profileContext, historySection, consensus,
                        sessionContext, candidates, message);
                composed = c2.text();
                inputTokens = c2.tokens();
                log.warn("[ContextComposer] 注入总预算超限，已压缩摘要: tokens={}", inputTokens);
            } else if (inputTokens > memoryProps.getInputMaxTokens()) {
                log.warn("[ContextComposer] 注入总预算超限（无摘要可压缩）: tokens={}", inputTokens);
            }
            if (inputTokens > memoryProps.getInputMaxTokens()) {
                profileContext = profileContextAssembler.assemble(
                        profilePort.getOrCreate(userId), memoryProps.getProfileMaxTokens() / 2);
                ComposedInput c3 = composeWithTokens(profileContext, historySection, consensus,
                        sessionContext, candidates, message);
                composed = c3.text();
                inputTokens = c3.tokens();
                log.warn("[ContextComposer] 注入总预算超限，已收紧画像段: tokens={}", inputTokens);
            }
        }
        return new ComposedContext(composed, inputTokens, profileContext, historySection);
    }

    private ComposedInput composeWithTokens(String profileContext, String historySection,
                                            String consensus, String sessionContext,
                                            String candidates, String message) {
        String composed = composeInput(profileContext, historySection, consensus,
                sessionContext, candidates, message);
        return new ComposedInput(composed, sessionMemoryPort.estimateTokens(composed));
    }

    private String composeInput(String profileContext, String historySection, String consensus,
                                String sessionContext, String candidates, String message) {
        StringBuilder input = new StringBuilder();
        if (!profileContext.isBlank()) {
            input.append(profileContext).append("\n\n");
        }
        if (!historySection.isBlank()) {
            input.append(historySection).append("\n\n");
        }
        if (consensus != null && !consensus.isBlank()) {
            input.append(consensus).append("\n\n");
        }
        if (sessionContext != null && !sessionContext.isBlank()) {
            input.append("【会话知识参考】\n").append(sessionContext).append("\n\n");
        }
        if (candidates != null && !candidates.isBlank() && !"[]".equals(candidates)) {
            input.append("【知识库检索候选景点】\n").append(candidates).append("\n\n");
        }
        input.append("【当前问题】\n").append(message);
        return input.toString();
    }

    private record ComposedInput(String text, int tokens) {
    }
}
