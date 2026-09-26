package com.travel.planning.memory.shortterm;

import com.travel.common.prompt.Markers;
import com.travel.common.trace.SpanCollector;
import com.travel.memory.MemoryFacade;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Map;
import com.travel.planning.memory.longterm.ProfileContextAssembler;
import com.travel.memory.shortterm.ShortTermMemoryProperties;
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

    /** S-B6c：Span 采集挂点（optional 注入，缺省自给） */
    private SpanCollector spanCollector = new SpanCollector();

    @Autowired(required = false)
    void setSpanCollector(SpanCollector spanCollector) {
        this.spanCollector = spanCollector;
    }


    private final ProfileContextAssembler profileContextAssembler;
    private final ShortTermMemoryProperties memoryProps;
    private final MemoryFacade memoryFacade;

    /** AB-5e：画像结构化查询（optional 注入；缺省=画像段原样）。 */
    private com.travel.memory.longterm.behavior.BehaviorProfileService behaviorProfileService;

    @Autowired(required = false)
    void setBehaviorProfileService(
            com.travel.memory.longterm.behavior.BehaviorProfileService behaviorProfileService) {
        this.behaviorProfileService = behaviorProfileService;
    }

    /** AB-5e：E-33 采集/注入开关（同 travel.profile.slot-enabled 键，默认关=关闭态逐字节等价）。 */
    @org.springframework.beans.factory.annotation.Value(
            "${travel.profile.slot-enabled:false}")
    private boolean profileSlotEnabled = false;

    /** AB-5e：测试直连（同包；生产装配走字段注入）。 */
    void setProfileSlotInjection(boolean enabled,
                                 com.travel.memory.longterm.behavior.BehaviorProfileService svc) {
        this.profileSlotEnabled = enabled;
        this.behaviorProfileService = svc;
    }

    public record ComposedContext(String text, int tokens, String profileContext,
                                  String historySection) {
    }

    public ComposedContext compose(String sessionId, Long userId,
                                   String profileContext, String historySection,
                                   String consensus, String sessionContext,
                                   String candidates, String message) {
        return compose(sessionId, userId, profileContext, historySection, consensus,
                sessionContext, candidates, message, "");
    }

    /** M23（E1）：九参重载——anchorSection（【锚定行程】段）插在历史之后、最新确认之前。 */
    public ComposedContext compose(String sessionId, Long userId,
                                   String profileContext, String historySection,
                                   String consensus, String sessionContext,
                                   String candidates, String message, String anchorSection) {
        return compose(sessionId, userId, profileContext, historySection, consensus,
                sessionContext, candidates, message, anchorSection, "");
    }

    /** M23b（E4）：十参重载——preferenceSection（【本轮偏好约束】段）紧随锚定段（丢弃顺位最低）。 */
    public ComposedContext compose(String sessionId, Long userId,
                                   String profileContext, String historySection,
                                   String consensus, String sessionContext,
                                   String candidates, String message, String anchorSection,
                                   String preferenceSection) {
        // S-B6c：记忆组装段 span（requestId 取自既有 TraceContext 同线程读）
        String rid = com.travel.planning.trace.TraceContext.active()
                ? com.travel.planning.trace.TraceContext.current().requestId : null;
        SpanCollector.Span span = rid == null ? null : spanCollector.startSpan(rid, "memory", "pipeline");
        try {
            ComposedContext ctx = composeInternal(sessionId, userId, profileContext, historySection,
                    consensus, sessionContext, candidates, message, anchorSection, preferenceSection);
            if (span != null) {
                spanCollector.endSpan(rid, span, "ok",
                        Map.of("inputTokens", ctx == null ? -1 : ctx.tokens(),
                               "composedChars", ctx == null || ctx.text() == null ? 0 : ctx.text().length()));
            }
            return ctx;
        } catch (RuntimeException e) {
            if (span != null) {
                spanCollector.endSpan(rid, span, "error", Map.of("error", String.valueOf(e.getMessage())));
            }
            throw e;
        }
    }

    /** S-B6c：原 compose 本体提取（包内可见） */
    ComposedContext composeInternal(String sessionId, Long userId,
                                    String profileContext, String historySection,
                                    String consensus, String sessionContext,
                                    String candidates, String message, String anchorSection,
                                    String preferenceSection) {
        // AB-5e：画像段增强（"用户常在 X 时段使用，偏好 Y 模型"）——E-33：travel.profile.slot-enabled
        // 默认 false=profileContext 原样返回=关闭态逐字节等价；fail-open 不影响组装主流程
        profileContext = augmentProfileContext(userId, profileContext);
        ComposedInput ci = composeWithTokens(profileContext, historySection, consensus,
                sessionContext, candidates, message, anchorSection, preferenceSection);
        String composed = ci.text();
        int inputTokens = ci.tokens();

        if (inputTokens > memoryProps.getInputMaxTokens()) {
            String summaryOnly = memoryFacade.getSummary(sessionId);
            boolean hasSummary = !summaryOnly.isBlank();
            if (hasSummary) {
                historySection = Markers.SESSION_SUMMARY + "\n" + summaryOnly;
                ComposedInput c1 = composeWithTokens(profileContext, historySection, consensus,
                        sessionContext, candidates, message, anchorSection, preferenceSection);
                composed = c1.text();
                inputTokens = c1.tokens();
            }
            if (inputTokens > memoryProps.getInputMaxTokens() && hasSummary) {
                int reserve = memoryFacade.estimateTokens(profileContext)
                        + memoryFacade.estimateTokens(Markers.CURRENT_QUESTION + "\n" + message) + 8;
                String cut = memoryFacade.truncateByTokens(
                        summaryOnly, Math.max(100, memoryProps.getInputMaxTokens() - reserve));
                historySection = Markers.SESSION_SUMMARY + "\n" + cut;
                ComposedInput c2 = composeWithTokens(profileContext, historySection, consensus,
                        sessionContext, candidates, message, anchorSection, preferenceSection);
                composed = c2.text();
                inputTokens = c2.tokens();
                log.warn("[ContextComposer] 注入总预算超限，已压缩摘要: tokens={}", inputTokens);
            } else if (inputTokens > memoryProps.getInputMaxTokens()) {
                log.warn("[ContextComposer] 注入总预算超限（无摘要可压缩）: tokens={}", inputTokens);
            }
            if (inputTokens > memoryProps.getInputMaxTokens()) {
                profileContext = profileContextAssembler.assemble(
                        memoryFacade.getOrCreateProfile(userId), memoryProps.getProfileMaxTokens() / 2);
                ComposedInput c3 = composeWithTokens(profileContext, historySection, consensus,
                        sessionContext, candidates, message, anchorSection, preferenceSection);
                composed = c3.text();
                inputTokens = c3.tokens();
                log.warn("[ContextComposer] 注入总预算超限，已收紧画像段: tokens={}", inputTokens);
            }
        }
        return new ComposedContext(composed, inputTokens, profileContext, historySection);
    }

    private ComposedInput composeWithTokens(String profileContext, String historySection,
                                            String consensus, String sessionContext,
                                            String candidates, String message, String anchorSection,
                                            String preferenceSection) {
        String composed = composeInput(profileContext, historySection, consensus,
                sessionContext, candidates, message, anchorSection, preferenceSection);
        return new ComposedInput(composed, memoryFacade.estimateTokens(composed));
    }

    private String composeInput(String profileContext, String historySection, String consensus,
                                String sessionContext, String candidates, String message, String anchorSection,
                                String preferenceSection) {
        StringBuilder input = new StringBuilder();
        if (!profileContext.isBlank()) {
            input.append(profileContext).append("\n\n");
        }
        if (!historySection.isBlank()) {
            input.append(historySection).append("\n\n");
        }
        // M23（E1）：锚定段紧随历史（锚定=讨论对象，先于结论/知识段；紧预算丢弃顺位低于最新确认）
        if (anchorSection != null && !anchorSection.isBlank()) {
            input.append(anchorSection).append("\n\n");
        }

        // M23b（E4）：偏好段紧随锚定（用户显式输入，丢弃顺位最低=最后被丢弃）
        if (preferenceSection != null && !preferenceSection.isBlank()) {
            input.append(preferenceSection).append("\n\n");
        }
        if (consensus != null && !consensus.isBlank()) {
            input.append(consensus).append("\n\n");
        }
        if (sessionContext != null && !sessionContext.isBlank()) {
            input.append(Markers.SESSION_KNOWLEDGE + "\n").append(sessionContext).append("\n\n");
        }
        if (candidates != null && !candidates.isBlank() && !"[]".equals(candidates)) {
            input.append(Markers.ATTRACTION_CANDIDATES + "\n").append(candidates).append("\n\n");
        }
        input.append(Markers.CURRENT_QUESTION + "\n").append(message);
        return input.toString();
    }

    private record ComposedInput(String text, int tokens) {
    }

    /**
     * AB-5e：画像段增强——按 6 段画像取使用次数最高时段+按使用次数最高模型，追加
     * "用户常在 X 时段使用，偏好 Y 模型"文本（方案 §一 AB-5e 原文案）。开关关/服务缺省/
     * userId 无效/画像段空白/无任何使用数据=原样返回（逐字节等价）；异常 fail-open。
     */
    String augmentProfileContext(Long userId, String profileContext) {
        if (!profileSlotEnabled || behaviorProfileService == null
                || userId == null || userId <= 0
                || profileContext == null || profileContext.isBlank()) {
            return profileContext;
        }
        try {
            var buckets = behaviorProfileService.getSlotProfile(userId);
            var models = behaviorProfileService.getModelUsageSummary(userId);
            int bestSlot = -1;
            int bestSlotCnt = 0;
            for (var b : buckets) {
                if (b.useCount() > bestSlotCnt) {
                    bestSlotCnt = b.useCount();
                    bestSlot = b.slotId();
                }
            }
            String modelText = null;
            long bestModelCnt = 0;
            for (var m : models) {
                if (m.useCount() > bestModelCnt) {
                    bestModelCnt = m.useCount();
                    modelText = m.modelKey();
                }
            }
            if (bestSlot < 0 && modelText == null) {
                return profileContext;
            }
            StringBuilder extra = new StringBuilder();
            if (bestSlot >= 0) {
                extra.append("用户常在 ").append(bestSlot * 4).append('-')
                        .append(bestSlot * 4 + 3).append(" 时段使用");
            }
            if (modelText != null) {
                extra.append(extra.length() == 0 ? "用户偏好 " : "，偏好 ")
                        .append(modelText).append(" 模型");
            }
            return profileContext + "\n" + extra;
        } catch (Exception e) {
            log.warn("[ContextComposer] 时段画像注入失败（不影响主流程）: {}", e.getMessage());
            return profileContext;
        }
    }
}
