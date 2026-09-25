package com.travel.planning.memory.pipeline;

import com.travel.common.config.ChatIntent;
import com.travel.common.trace.SpanCollector;
import com.travel.memory.MemoryFacade;
import org.springframework.beans.factory.annotation.Autowired;
import com.travel.planning.memory.knowledge.KnowledgeRetrievalService;
import com.travel.planning.memory.knowledge.RagInjectionProperties;
import com.travel.planning.memory.knowledge.SessionKnowledgeWriter;
import com.travel.planning.memory.shortterm.ContextComposer;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M3-16：MessagePipeline 步骤 7「预算」。
 * 检索注入（候选/会话知识/共识）+ 上下文组装与四档预算兜底从 ChatService 抽出为独立可测步骤。
 *
 * <p>M4-5 增量（均在 compose 内做前置处理，BudgetContext 契约不变）：</p>
 * <ul>
 *   <li>M4-5a 在线相关性 Judge：enabled 且 PLANNING/REFINE 意图时用轻模型判定两段注入相关性，
 *       不相关段置空；RECALL/PROFILE/CHAT/FUNCTIONAL 豁免（回顾类必用会话知识）；默认关；</li>
 *   <li>M4-5b 会话知识二次取父：itinerary_day 命中按 seq 前缀取回该行程全部天块替换命中子块，
 *       解决 topK 截断丢天问题；RECALL 骨架与【会话知识参考】同源受益，无需改路由；默认开、
 *       取父失败降级保留原命中。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ChatBudgetStep implements ChatPipelineStep {

    /** B3.2：步骤顺序——步骤 7「预算」（依据 R7-pipeline-mapping 现发送链步骤序 7，ChatService :534）。 */
    static final int STEP_ORDER = 7;

    @Override
    public int order() {
        return STEP_ORDER;
    }

    /** S-B6a：Span 采集挂点（Z-4e：构造注入统一——SpanCollector 为 @Component bean，注入语义与 optional setter 等价） */
    private final SpanCollector spanCollector;

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
    private final ContextComposer contextComposer;
    private final RagInjectionProperties ragInjectionProperties;
    private final RagJudge ragJudge;
    private final RagJudgeProperties ragJudgeProperties;
    private final MemoryFacade memoryFacade;

    /** MR-C3：语义召回注入 top-K（{@code travel.memory.recall-semantic-top-k}，默认 0=关闭，E-33） */
    @Value("${travel.memory.recall-semantic-top-k:0}")
    private int recallSemanticTopK;

    /** 配置面（单测注入用；生产由 @Value 绑定） */
    void setRecallSemanticTopK(int recallSemanticTopK) {
        this.recallSemanticTopK = recallSemanticTopK;
    }

    /**
     * 组装 画像+历史+共识+会话知识+候选+当前问题，并执行四档 token 预算兜底
     * （F63/F66 预检索门控、F78 C3 会话知识检索、F83 topK、F85 共识、M3-9 兜底、M4-5a/b 前置处理）。
     */
    public BudgetContext compose(String sessionId, Long userId, ChatIntent intent,
                                 String message, String profileContext, String historySection) {
        return compose(sessionId, userId, intent, message, profileContext, historySection,
                "", java.util.List.of());
    }

    /** M23（E1）：八参重载——anchorSection 由 ChatService 渲染传入；anchorIds 过滤行程切片。 */
    public BudgetContext compose(String sessionId, Long userId, ChatIntent intent,
                                 String message, String profileContext, String historySection,
                                 String anchorSection, java.util.List<Long> anchorIds) {
        return compose(sessionId, userId, intent, message, profileContext, historySection,
                anchorSection, anchorIds, "", "");
    }

    /** M23b（E4）：十参重载——preferenceSection 注入 + querySuffix 检索拼接。 */
    public BudgetContext compose(String sessionId, Long userId, ChatIntent intent,
                                 String message, String profileContext, String historySection,
                                 String anchorSection, java.util.List<Long> anchorIds,
                                 String preferenceSection, String preferenceQuerySuffix) {
        // S-B6a：预算/注入段 span（requestId 取自既有 TraceContext 同线程上下文——不新增 ThreadLocal）
        String rid = com.travel.planning.trace.TraceContext.active()
                ? com.travel.planning.trace.TraceContext.current().requestId : null;
        SpanCollector.Span span = rid == null ? null : spanCollector.startSpan(rid, "budget", "pipeline");
        try {
            BudgetContext ctx = composeInternal(sessionId, userId, intent, message, profileContext,
                    historySection, anchorSection, anchorIds, preferenceSection, preferenceQuerySuffix);
            if (span != null) {
                Map<String, Object> attrs = new LinkedHashMap<>();
                attrs.put("intent", String.valueOf(intent));
                attrs.put("inputTokens", ctx.inputTokens());
                attrs.put("composedChars", ctx.composed() == null ? 0 : ctx.composed().length());
                spanCollector.endSpan(rid, span, "ok", attrs);
            }
            return ctx;
        } catch (RuntimeException e) {
            if (span != null) {
                spanCollector.endSpan(rid, span, "error", Map.of("intent", String.valueOf(intent)));
            }
            throw e;
        }
    }

    /** S-B6a：原 compose 本体（包内可见提取，便于挂 span） */
    BudgetContext composeInternal(String sessionId, Long userId, ChatIntent intent,
                                  String message, String profileContext, String historySection,
                                  String anchorSection, java.util.List<Long> anchorIds,
                                  String preferenceSection, String preferenceQuerySuffix) {
        // F63：确定性预检索注入——把知识库候选景点放入上下文，确保聊天链消费知识库。
        // F66：非检索意图（画像/偏好/闲聊类）跳过预检索，避免无关候选污染上下文。
        // M4-2：topK 配置化（travel.rag.*，默认值等于 F63/F83 硬编码）
        String candidates = needsKnowledgeRetrievalByIntent(intent)
                ? knowledgeRetrievalService.retrieveCandidates(
                        message + (preferenceQuerySuffix == null ? "" : preferenceQuerySuffix),
                        ragInjectionProperties.getAttractionCandidatesTopK()) : "[]";
        // Phase C/F78（C3）：按需检索本会话历史知识（结构化，供共识层与注入共用，只检索一次）
        // F83：topK 放大（默认 8），避免类型加分把行程切片挤出注入（E4 召回问题）
        List<Map<String, Object>> sessionHits = sessionKnowledgeWriter.searchStructured(
                sessionId, message, ragInjectionProperties.getSessionContextTopK());
        // MR-C3：语义召回注入——按前缀附加召回近会话蒸馏产物（type∈{semantic,entity}，seq 前缀
        // distill:，与 C2 落库口径同源）。K=travel.memory.recall-semantic-top-k，默认 0=关闭：
        // 零额外调用、sessionHits 原样（逐字节等价，E-33）；按 seq 去重、上限 K 条。
        if (recallSemanticTopK > 0) {
            List<Map<String, Object>> distilled = sessionKnowledgeWriter.fetchBySeqPrefix(sessionId, "distill:");
            if (distilled != null && !distilled.isEmpty()) {
                Set<String> seenSeqs = new HashSet<>();
                for (Map<String, Object> h : sessionHits) {
                    seenSeqs.add(String.valueOf(h.get("seq")));
                }
                List<Map<String, Object>> merged = new ArrayList<>(sessionHits);
                for (Map<String, Object> h : distilled) {
                    if (merged.size() >= sessionHits.size() + recallSemanticTopK) {
                        break;
                    }
                    Object type = h.get("type");
                    if (("semantic".equals(type) || "entity".equals(type))
                            && seenSeqs.add(String.valueOf(h.get("seq")))) {
                        merged.add(h);
                    }
                }
                sessionHits = merged;
            }
        }
        // M23（E2）：锚定非空时，itinerary_day 切片只保留锚定集合内的行程——
        // 注意力切换的结构保证（AI 不再沿用切换前的旧规划）
        if (anchorIds != null && !anchorIds.isEmpty()) {
            sessionHits = sessionHits.stream()
                    .filter(h -> {
                        Object seq = h.get("seq");
                        if (seq == null || !seq.toString().startsWith("itin:")) {
                            return true; // 非行程切片不受过滤
                        }
                        return anchorIds.stream().anyMatch(id -> seq.toString().startsWith("itin:" + id + ":"));
                    })
                    .toList();
        }
        // M4-5b：二次取父——itinerary_day 命中被 topK 截断丢天时，按 seq 前缀补全该行程全部天块
        sessionHits = expandItineraryParentView(sessionId, sessionHits);
        String sessionContext = SessionKnowledgeWriter.format(sessionHits);
        // F85：会话事实共识——同主题 feedback 覆盖旧 constraint，注入【会话最新确认】
        String consensus = memoryFacade.renderFacts(memoryFacade.consolidateFacts(sessionHits));
        // M4-5a：在线相关性 Judge——不相关段置空（fail-open，见 RagJudge）
        if (judgeApplicable(intent, sessionContext, candidates)) {
            RagJudge.JudgeResult verdict = ragJudge.judge(message, sessionContext, candidates);
            if (!verdict.sessionKnowledgeRelevant()) {
                sessionContext = "";
            }
            if (!verdict.attractionCandidatesRelevant()) {
                candidates = "[]";
            }
        }
        // M3-9：上下文组装与四档预算兜底收敛到 ContextComposer（行为等价）
        ContextComposer.ComposedContext cc = contextComposer.compose(sessionId, userId,
                profileContext, historySection, consensus, sessionContext, candidates, message,
                anchorSection);
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

    /**
     * M4-5a：Judge 适用条件——开关开启 + 意图 ∈ {PLANNING, REFINE}
     * （RECALL/PROFILE/CHAT/FUNCTIONAL 豁免：回顾类必须消费会话知识）+ 至少一段非空。
     */
    private boolean judgeApplicable(ChatIntent intent, String sessionContext, String candidates) {
        if (!ragJudgeProperties.isEnabled()) {
            return false;
        }
        if (intent != ChatIntent.PLANNING && intent != ChatIntent.REFINE) {
            return false;
        }
        return !sessionContext.isBlank() || !"[]".equals(candidates);
    }

    /**
     * M4-5b：会话知识二次取父（by-prefix）。
     *
     * <p>解析 itinerary_day 命中的 seq 提取行程 id（去重，通常 1 个）→ 每个行程 id 调一次
     * {@link SessionKnowledgeWriter#fetchBySeqPrefix} 取回<b>全部</b>天块，<b>替换</b>原命中子块
     * （其余类型条目原样保留，插入位置为该行程首个命中处，排序稳定）。取回块沿用被替换块的
     * 最小 score，保持与原命中列表的排序稳定性。取父失败/关闭时原样返回（回归零风险）。</p>
     */
    private List<Map<String, Object>> expandItineraryParentView(String sessionId,
                                                                List<Map<String, Object>> hits) {
        if (!ragInjectionProperties.isParentContextEnabled() || hits == null || hits.isEmpty()) {
            return hits;
        }
        // 1. 收集命中的行程 id（去重）与各行程被替换块的最小 score（排序稳定锚点）
        Map<String, Double> minScoreByItin = new LinkedHashMap<>();
        for (Map<String, Object> hit : hits) {
            if (!isItineraryDay(hit)) {
                continue;
            }
            String itinId = SessionKnowledgeWriter.planIdOf(hit);
            if (itinId != null) {
                minScoreByItin.merge(itinId, scoreOf(hit), Math::min);
            }
        }
        if (minScoreByItin.isEmpty()) {
            return hits;
        }
        // 2. 逐命中重建列表：行程命中替换为父视图天块，其余类型原样保留
        List<Map<String, Object>> out = new ArrayList<>(hits.size());
        Set<String> expanded = new HashSet<>();
        Set<String> failed = new HashSet<>();
        for (Map<String, Object> hit : hits) {
            String itinId = isItineraryDay(hit) ? SessionKnowledgeWriter.planIdOf(hit) : null;
            if (itinId == null || !minScoreByItin.containsKey(itinId)) {
                out.add(hit); // 非 itinerary_day / seq 异常：原样保留
                continue;
            }
            if (expanded.contains(itinId)) {
                continue; // 同行程后续命中子块已被父视图覆盖（按 chunkId/id 天然去重）
            }
            if (failed.contains(itinId)) {
                out.add(hit); // 取父已失败的行程：保留原命中子块，不重试
                continue;
            }
            List<Map<String, Object>> parentDays =
                    sessionKnowledgeWriter.fetchBySeqPrefix(sessionId, "itin:" + itinId + ":");
            if (parentDays.isEmpty()) {
                failed.add(itinId);
                out.add(hit); // 取父失败降级：保留原命中子块（同行程后续命中同样保留）
                continue;
            }
            expanded.add(itinId);
            double minScore = minScoreByItin.getOrDefault(itinId, 0.0);
            for (Map<String, Object> day : parentDays) {
                Map<String, Object> m = new LinkedHashMap<>(day);
                m.put("score", minScore); // 沿用被替换块的最小 score，保持排序稳定
                out.add(m);
            }
        }
        return out;
    }

    private static boolean isItineraryDay(Map<String, Object> hit) {
        return "itinerary_day".equals(String.valueOf(hit.getOrDefault("type", "")));
    }

    private static double scoreOf(Map<String, Object> hit) {
        Object v = hit.get("score");
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }
}
