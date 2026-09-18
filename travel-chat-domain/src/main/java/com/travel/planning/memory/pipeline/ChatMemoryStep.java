package com.travel.planning.memory.pipeline;

import com.travel.planning.prompt.Markers;
import com.travel.memory.MemoryFacade;
import com.travel.planning.memory.longterm.ProfileContextAssembler;
import com.travel.memory.longterm.behavior.BehaviorProfileService;
import com.travel.memory.longterm.behavior.BehaviorProfileProperties;
import com.travel.memory.longterm.behavior.BehaviorSections;
import com.travel.memory.shortterm.ShortTermMemoryProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * M3-15：MessagePipeline 步骤 6「记忆」。
 * 画像 + 历史/摘要段组装从 ChatService 抽出为独立可测步骤。
 *
 * <p>HC-4（方案 §六 2026-09-13 修订版）：assemble 内两独立读段（画像段 / 短期记忆段）
 * 以 {@link CompletableFuture#supplyAsync} 并行（专用 2 线程 Executor，禁用公共
 * ForkJoinPool，守护线程随 Bean 关闭回收）；等待上限 2s，超时或任一段异常即
 * <b>取消在飞任务并降级串行组装</b>（读段幂等，重取保行为零回归）。
 * 开关 {@code perf.prepare-parallel.enabled}（默认 true，false 走原串行路径）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMemoryStep implements ChatPipelineStep {

    /** B3.2：步骤顺序——M3-15 步骤 6「记忆」（依据 R7-pipeline-mapping 现发送链步骤序 6，ChatService :484）。 */
    static final int STEP_ORDER = 6;

    /** HC-4：并行等待上限（超时降级串行）。 */
    private static final long PARALLEL_TIMEOUT_SECONDS = 2;

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

    private final ProfileContextAssembler profileContextAssembler;
    private final ShortTermMemoryProperties memoryProps;
    // M17-3：行为画像注入（inject-enabled=false 时零调用零注入，行为逐字节等价）
    private final BehaviorProfileService behaviorProfileService;
    private final BehaviorProfileProperties behaviorProps;
    private final MemoryFacade memoryFacade;

    /** HC-4：并行开关（默认 true；false 走原串行路径）。 */
    @Value("${perf.prepare-parallel.enabled:true}")
    private boolean parallelEnabled = true;

    /** HC-4：记忆两读段专用 Executor（2 线程对应两读段；禁用公共 ForkJoinPool；守护线程）。 */
    private final ExecutorService parallelExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "chat-memory-parallel");
        t.setDaemon(true);
        return t;
    });

    /** Bean 关闭时回收专用 Executor（方案修订版：生命周期随 Bean）。 */
    @PreDestroy
    void shutdownParallelExecutor() {
        parallelExecutor.shutdown();
    }

    /**
     * 组装 画像 + (摘要+滑动窗口 | 原文历史)（F50/F55/F57/F60 语义不变）。
     * HC-4：两读段并行（2s 超时/异常降级串行值，保行为）；开关关闭走纯串行。
     */
    public MemoryContext assemble(Long userId, String sessionId) {
        if (!parallelEnabled) {
            return assembleSerial(userId, sessionId);
        }
        CompletableFuture<ProfilePart> profileFuture = null;
        CompletableFuture<HistoryPart> historyFuture = null;
        try {
            profileFuture = CompletableFuture.supplyAsync(() -> profilePart(userId), parallelExecutor);
            historyFuture = CompletableFuture.supplyAsync(() -> historyPart(sessionId), parallelExecutor);
            CompletableFuture.allOf(profileFuture, historyFuture).get(PARALLEL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            ProfilePart profile = profileFuture.join();
            HistoryPart history = historyFuture.join();
            return combine(profile, history);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelQuietly(profileFuture, historyFuture);
            log.warn("[ChatMemoryParallel] 并行等待被中断，降级串行组装: sessionId={}", sessionId);
            return assembleSerial(userId, sessionId);
        } catch (ExecutionException | TimeoutException e) {
            cancelQuietly(profileFuture, historyFuture);
            log.warn("[ChatMemoryParallel] 并行段异常/超时，降级串行组装: sessionId={}, error={}",
                    sessionId, e.getMessage());
            return assembleSerial(userId, sessionId);
        }
    }

    /** 原串行路径（开关关闭/并行降级时使用；与并行段共用同一套读段实现，语义单源）。 */
    private MemoryContext assembleSerial(Long userId, String sessionId) {
        return combine(profilePart(userId), historyPart(sessionId));
    }

    private MemoryContext combine(ProfilePart profile, HistoryPart history) {
        return new MemoryContext(profile.profileContext(), history.historySection(),
                history.summaryUsed(), history.summaryTriggered(), history.turns(), history.totalHistoryTokens());
    }

    /** 读段①：行为段（条件注入）+ 画像段。 */
    private ProfilePart profilePart(Long userId) {
        String behaviorSection = behaviorSectionOrNull(userId);
        String profileContext;
        if (behaviorSection == null) {
            // M17-3 红线：inject 关闭/无可靠行为画像时走原路径（与现状逐字节等价）
            profileContext = profileContextAssembler.assemble(memoryFacade.getOrCreateProfile(userId));
        } else {
            profileContext = profileContextAssembler.assemble(
                    memoryFacade.getOrCreateProfile(userId), behaviorSection);
        }
        return new ProfilePart(profileContext);
    }

    /** 读段②：短期记忆段（原文历史/摘要触发/滑动窗口 + 轮数与全量 token 统计）。 */
    private HistoryPart historyPart(String sessionId) {
        String rawHistory = memoryFacade.composeHistory(sessionId, memoryProps.getMaxTurns());
        int turns = memoryFacade.countUserTurns(sessionId);
        // F57：以全量汇总 token 作为触发依据（截断前统计），配合轮数触发。
        int totalHistoryTokens = memoryFacade.totalHistoryTokens(sessionId);
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
            memoryFacade.summarizeAsync(sessionId);
            String summary = memoryFacade.getSummary(sessionId);
            if (summary.isBlank()) {
                // 摘要尚未生成（首轮触发）：本轮仍用原文窗口。
                historySection = rawHistory;
            } else {
                StringBuilder sb = new StringBuilder(Markers.SESSION_SUMMARY + "\n").append(summary);
                String recent = memoryFacade.composeRecentWindow(sessionId, memoryProps.getRecentWindowTurns());
                if (!recent.isBlank()) {
                    sb.append("\n\n【最近对话】\n").append(recent);
                }
                historySection = sb.toString();
                summaryUsed = true;
            }
        } else {
            historySection = rawHistory;
        }
        return new HistoryPart(historySection, summaryUsed, summaryTriggered, turns, totalHistoryTokens);
    }

    private static void cancelQuietly(CompletableFuture<?>... futures) {
        for (CompletableFuture<?> f : futures) {
            if (f != null) {
                f.cancel(true);
            }
        }
    }

    private record ProfilePart(String profileContext) {
    }

    private record HistoryPart(String historySection, boolean summaryUsed,
                               boolean summaryTriggered, int turns, int totalHistoryTokens) {
    }

    /**
     * M17-3：行为特征段（inject-enabled 且画像可靠且可渲染出内容时非 null）。
     */
    private String behaviorSectionOrNull(Long userId) {
        if (!behaviorProps.isInjectEnabled()) {
            return null;
        }
        return memoryFacade.getBehavior(userId)
                .filter(behaviorProfileService::isReliable)
                .map(row -> BehaviorSections.render(row, behaviorProps.getInjectMaxTokens()))
                .orElse(null);
    }
}
