package com.travel.planning.memory.pipeline;

import com.travel.planning.prompt.Markers;
import com.travel.planning.memory.longterm.ProfileContextAssembler;
import com.travel.planning.memory.longterm.ProfilePort;
import com.travel.planning.memory.longterm.behavior.BehaviorProfileService;
import com.travel.planning.memory.longterm.behavior.BehaviorProfileProperties;
import com.travel.planning.memory.longterm.behavior.BehaviorSections;
import com.travel.planning.memory.shortterm.SessionMemoryPort;
import com.travel.planning.memory.shortterm.ShortTermMemoryProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * M3-15：MessagePipeline 步骤 6「记忆」。
 * 画像 + 历史/摘要段组装从 ChatService 抽出为独立可测步骤。
 */
@Component
@RequiredArgsConstructor
public class ChatMemoryStep implements ChatPipelineStep {

    /** B3.2：步骤顺序——M3-15 步骤 6「记忆」（依据 R7-pipeline-mapping 现发送链步骤序 6，ChatService :484）。 */
    static final int STEP_ORDER = 6;

    @Override
    public int order() {
        return STEP_ORDER;
    }

    /**
     * 记忆组装结果：画像段、历史/摘要段、摘要标记与触发依据（供日志与后续步骤使用）。
     */
    public record MemoryContext(String profileContext, String historySection,
                                boolean summaryUsed, boolean summaryTriggered,
                                int turns, int totalHistoryTokens) {
    }

    private final ProfilePort profilePort;
    private final ProfileContextAssembler profileContextAssembler;
    private final SessionMemoryPort sessionMemoryPort;
    private final ShortTermMemoryProperties memoryProps;
    // M17-3：行为画像注入（inject-enabled=false 时零调用零注入，行为逐字节等价）
    private final BehaviorProfileService behaviorProfileService;
    private final BehaviorProfileProperties behaviorProps;

    /**
     * 组装 画像 + (摘要+滑动窗口 | 原文历史)（F50/F55/F57/F60 语义不变）。
     */
    public MemoryContext assemble(Long userId, String sessionId) {
        String behaviorSection = behaviorSectionOrNull(userId);
        String profileContext;
        if (behaviorSection == null) {
            // M17-3 红线：inject 关闭/无可靠行为画像时走原路径（与现状逐字节等价）
            profileContext = profileContextAssembler.assemble(profilePort.getOrCreate(userId));
        } else {
            profileContext = profileContextAssembler.assemble(
                    profilePort.getOrCreate(userId), behaviorSection);
        }
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
                StringBuilder sb = new StringBuilder(Markers.SESSION_SUMMARY + "\n").append(summary);
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
        return new MemoryContext(profileContext, historySection, summaryUsed, summaryTriggered,
                turns, totalHistoryTokens);
    }

    /**
     * M17-3：行为特征段（inject-enabled 且画像可靠且可渲染出内容时非 null）。
     */
    private String behaviorSectionOrNull(Long userId) {
        if (!behaviorProps.isInjectEnabled()) {
            return null;
        }
        return behaviorProfileService.getBehavior(userId)
                .filter(behaviorProfileService::isReliable)
                .map(row -> BehaviorSections.render(row, behaviorProps.getInjectMaxTokens()))
                .orElse(null);
    }
}
