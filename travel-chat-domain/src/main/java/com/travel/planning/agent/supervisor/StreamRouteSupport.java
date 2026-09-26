package com.travel.planning.agent.supervisor;

import com.travel.common.config.ChatIntent;
import com.travel.common.trace.SpanCollector;
import com.travel.planning.agent.support.AttractionGroundingChecker;
import com.travel.planning.agent.support.ItineraryConflictPort;
import com.travel.planning.agent.support.ItineraryVersionPort;
import com.travel.planning.memory.knowledge.RagQualityCounters;
import lombok.extern.slf4j.Slf4j;
import com.travel.planning.memory.knowledge.SessionContextChunker;
import com.travel.planning.memory.knowledge.SessionKnowledgeWriter;

/**
 * AA-7：流式/阻塞路由共用的"标注/幻觉计数/回写组装"静态方法集。
 *
 * <p>拆分自 {@code ChatRoutingStep}（Z-4e 后 561 行）——route() 阻塞 default 分支与
 * routeStream() 图流分支此前各持一份同构段（S-D1 标注+S-D2 计数+M8-9 切片+M13-2 回写），
 * 本类以静态方法集收敛；方法体自原位<b>逐字搬运</b>（仅 this.X 依赖改参数注入，
 * 注释/日志/异常语义零变），ChatRoutingStep 保留同名薄委托（调用点行为零变）。</p>
 */
@Slf4j
public final class StreamRouteSupport {

    private StreamRouteSupport() {
    }

    /** S-D2 计数依赖（原 ChatRoutingStep 私有工具逐字搬运）。 */
    static int countOccurrences(String text, String mark) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0, idx = 0;
        while ((idx = text.indexOf(mark, idx)) >= 0) {
            count++;
            idx += mark.length();
        }
        return count;
    }

    /**
     * M8-2 引用校验观测 + S-D1 strict-attraction 未命中标注 + S-D2 幻觉标注计数
     * （route() 与 routeStream() 图流分支共用段逐字合并；返回标注后全文）。
     */
    public static String annotateGroundingWithCount(AttractionGroundingChecker groundingChecker,
                                                    String composed, String answer,
                                                    RagQualityCounters ragQualityCounters) {
        SupervisorResponseSupport.recordGrounding(groundingChecker, composed, answer);
        String beforeAnnotate = answer;
        String annotatedAnswer = SupervisorResponseSupport.annotateGrounding(
                groundingChecker, composed, answer);
        // S-D2：幻觉标注计数入 Redis metrics 族（RK-13 通道）
        String mark = AttractionGroundingChecker.UNKNOW_SOURCE_MARK;
        int flagged = countOccurrences(annotatedAnswer, mark) - countOccurrences(beforeAnnotate, mark);
        if (flagged > 0 && ragQualityCounters != null) {
            ragQualityCounters.recordHallucinationFlagged(flagged);
        }
        return annotatedAnswer;
    }

    /**
     * M8-9：把 Supervisor 规划结果按天切片写入当前会话知识。
     *
     * <p>先按 seq 前缀 {@code itin:<sessionId>:} 删除旧版本（REFINE/重生成覆盖），
     * 再写入新切片；任一步失败仅 WARN（残留旧切片只影响观测，不阻断主流程）。</p>
     */
    public static void writeItineraryChunks(SessionKnowledgeWriter sessionKnowledgeWriter,
                                            SessionContextChunker sessionContextChunker,
                                            String sessionId, String routePlanJson) {
        if (sessionId == null || sessionId.isBlank()
                || routePlanJson == null || routePlanJson.isBlank()) {
            return;
        }
        try {
            String trimmed = routePlanJson.trim();
            // state 的 routePlan 是 {"days":[...]}；chunkItinerary 期望 {"routePlan": {...}}
            String itineraryJson = trimmed.startsWith("{") && trimmed.contains("\"days\"")
                    ? "{\"routePlan\":" + trimmed + "}" : trimmed;
            String prefix = "itin:" + sessionId + ":";
            sessionKnowledgeWriter.deleteBySeqPrefix(sessionId, prefix);
            sessionKnowledgeWriter.writeAsync(sessionId,
                    sessionContextChunker.chunkItinerary(sessionId, itineraryJson, null));
            log.info(
                    "[ChatRouting] itinerary_day 切片已写入会话知识: sessionId={}", sessionId);
        } catch (Exception e) {
            log.warn(
                    "[ChatRouting] itinerary_day 切片写入失败（不影响主流程）: sessionId={}, error={}",
                    sessionId, e.getMessage());
        }
    }

    /** M13-2：聊天规划/REFINE 结果同步为行程资产（create-on-chat / REFINE 回写建版）。
     *  仅观测/资产层变化，不参与回答组装；失败静默降级。 */
    public static Long writebackIfEnabled(boolean itineraryWritebackEnabled,
                                          ItineraryVersionPort itineraryVersionPort,
                                          SpanCollector spanCollector,
                                          ChatIntent intent, Long userId, String sessionId,
                                          String userInput, String routePlanJson, String budgetJson) {
        final Long[] writtenIdHolder = {null}; // M23（P-D）：lambda 内赋值用数组持有
        if (!itineraryWritebackEnabled || itineraryVersionPort == null
                || intent != ChatIntent.PLANNING && intent != ChatIntent.REFINE) {
            return null;
        }
        if (routePlanJson == null || routePlanJson.isBlank()) {
            // M20-1：不再静默——2026-09-06 实证 REFINE 因图流输出丢失而无声跳过建版，
            // 用户仅在详情页发现"少了一个版本"。WARN 暴露原因供诊断。
            log.warn(
                    "[ItineraryWriteback] 跳过回写（routePlan 为空，图流子Agent 输出未合并或走了直答兜底）: "
                            + "sessionId={}, intent={}, answer将不建版", sessionId, intent);
            return null;
        }
        // S-B6c：回写桥 span（requestId 取自既有 TraceContext 同线程读——不新增 ThreadLocal）
        String rid = com.travel.planning.trace.TraceContext.active()
                ? com.travel.planning.trace.TraceContext.current().requestId : null;
        SpanCollector.Span wbSpan = rid == null ? null : spanCollector.startSpan(rid, "writeback", "bridge");
        try {
            java.util.Optional<Long> itineraryId = itineraryVersionPort.syncAfterPlanning(
                    userId, sessionId, userInput, routePlanJson, budgetJson);
            itineraryId.ifPresent(id -> {
                writtenIdHolder[0] = id;
                log.info(
                        "[ItineraryWriteback] 行程资产已同步: itineraryId={}, sessionId={}, intent={}",
                        id, sessionId, intent);
            });
            if (wbSpan != null) {
                spanCollector.endSpan(rid, wbSpan, "ok",
                        java.util.Map.of("written", itineraryId.isPresent(),
                                "itineraryId", itineraryId.orElse(-1L)));
            }
        } catch (Exception e) {
            if (wbSpan != null) {
                spanCollector.endSpan(rid, wbSpan, "error",
                        java.util.Map.of("error", String.valueOf(e.getMessage())));
            }
            log.warn(
                    "[ItineraryWriteback] 同步失败（不影响主流程）: sessionId={}, error={}",
                    sessionId, e.getMessage());
        }
        return writtenIdHolder[0];
    }

    /** M9-4：规划成功后做观测级冲突校验（只写 trace，不阻断、不重试）。 */
    public static void observeConflictIfEnabled(boolean conflictObserveEnabled,
                                                ItineraryConflictPort itineraryConflictPort,
                                                String composed, String routePlanJson) {
        if (!conflictObserveEnabled || itineraryConflictPort == null) {
            return;
        }
        SupervisorResponseSupport.recordChatConflict(
                itineraryConflictPort, routePlanJson, composed);
    }
}
