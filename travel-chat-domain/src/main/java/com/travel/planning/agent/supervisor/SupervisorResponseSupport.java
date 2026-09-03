package com.travel.planning.agent.supervisor;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.travel.common.util.AgentOutputUtils;
import com.travel.common.util.JsonUtils;
import com.travel.planning.agent.support.AttractionGroundingChecker;
import com.travel.planning.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * M6-58/T9 Step4：Supervisor 最终回答组装与状态文本工具（从 TravelSupervisorAgent 迁出）。
 *
 * <p>纯静态、零状态；被阻塞/图流执行器与门面共用。行为与迁移前逐字节等价：
 * F23 D1 安全文本提取（Optional/AssistantMessage/String 解包）、
 * F84 GraphResponse 防泄漏置空、M3-2/P2-10 state key 分派排版、
 * M6-56/T5 代码围栏去除（AgentOutputUtils）均原样保留。</p>
 */
@Slf4j
public final class SupervisorResponseSupport {

    private SupervisorResponseSupport() {
    }

    /**
     * 将最终 state 中的子 Agent 输出组装为面向用户的行程规划回答。
     *
     * <p>顺序：偏好分析 → 推荐景点 → 每日行程 → 预算估算；缺失段落自动跳过；
     * 全部缺失时回退 messages 中最后一条非路由 AssistantMessage，
     * 仍为空则返回友好提示。</p>
     */
    static String buildFinalResponse(OverAllState state) {
        List<String> parts = new ArrayList<>();
        // M3-2/P2-10：排版按 state key 分派（不依赖中文标题）
        addSection(parts, "preference", "偏好分析", toText(state.value("preference")));
        addSection(parts, "attractions", "推荐景点", toText(state.value("attractions")));
        addSection(parts, "routePlan", "每日行程", toText(state.value("routePlan")));
        addSection(parts, "budgetEstimate", "预算估算", toText(state.value("budgetEstimate")));
        if (!parts.isEmpty()) {
            return String.join("\n\n", parts);
        }
        String fallback = lastMeaningfulMessage(state);
        return fallback.isBlank() ? "抱歉，未能生成行程规划，请稍后重试。" : fallback;
    }

    private static void addSection(List<String> parts, String key, String title, String text) {
        if (text != null && !text.isBlank()) {
            String cleaned = AgentOutputUtils.stripCodeFence(text);
            String formatted = SupervisorResponseFormatter.format(key, cleaned);
            parts.add("【" + title + "】\n" + (formatted != null ? formatted : cleaned));
        }
    }

    /**
     * 安全提取 outputKey 文本：递归解包 {@link Optional}，兼容
     * {@link AssistantMessage} / {@link String}（复用 TravelWorkflowBuilder 已验证模式，
     * F23 D1 修复）。防御值为 Map/其他类型时退化 toString，避免下游强转崩坏。
     */
    static String toText(Object value) {
        if (value == null) return "";
        if (value instanceof Optional<?> opt) return toText(opt.orElse(null));
        if (value instanceof String s) return s;
        if (value instanceof AssistantMessage am) return am.getText();
        // F84：框架内部 GraphResponse 对象（如某轮子 Agent 输出异常时被写入 state）
        // toString 为 "com.alibaba.cloud.ai.graph.GraphResponse@hex"，泄漏进用户回答。
        // 该对象无可读文本，直接置空跳过对应段落。
        if (value instanceof com.alibaba.cloud.ai.graph.GraphResponse) return "";
        return value.toString();
    }

    /** 回退：取 messages 中最后一条非路由决策的 AssistantMessage。 */
    private static String lastMeaningfulMessage(OverAllState state) {
        Object messages = state.value("messages").orElse(null);
        if (messages instanceof List<?> list) {
            for (int i = list.size() - 1; i >= 0; i--) {
                Object m = list.get(i);
                if (!(m instanceof AssistantMessage am)) {
                    continue;
                }
                String text = am.getText();
                if (text == null || text.isBlank()) {
                    continue;
                }
                if (PlanningHeuristics.looksLikeRoutingDecision(text)) {
                    continue;
                }
                return text;
            }
        }
        return "";
    }

    static int textLen(OverAllState state, String key) {
        String t = toText(state.value(key));
        return t != null ? t.length() : 0;
    }

    /** F66：四个子 Agent 输出键是否至少有一个非空（判断是否真正走了规划流程） */
    static boolean hasSectionOutput(OverAllState state) {
        return !toText(state.value("preference")).isBlank()
                || !toText(state.value("attractions")).isBlank()
                || !toText(state.value("routePlan")).isBlank()
                || !toText(state.value("budgetEstimate")).isBlank();
    }

    /**
     * M8-2：生成端引用校验（Grounding Check）——组装回答后调用，结果写 TraceContext。
     *
     * <p>候选名从组合输入（含【知识库检索候选景点】JSON）提取，输出名从最终回答的
     * 【推荐景点】段落提取；候选为空/输出不可解析时跳过（观测模式，不阻断回答）。
     * 本方法在调用线程执行（SupervisorGraphExecutor 的 future.get() 返回后），
     * TraceContext ThreadLocal 可用。</p>
     */
    public static void recordGrounding(AttractionGroundingChecker checker,
                                       String composed, String response) {
        if (checker == null || !TraceContext.active()) {
            return;
        }
        Set<String> candidates = extractCandidateNames(composed);
        if (candidates.isEmpty()) {
            return;
        }
        String attractionsText = extractSection(response, "推荐景点");
        if (attractionsText == null) {
            attractionsText = response;
        }
        // M8-7：改为 checkText——真实回答是 SupervisorResponseFormatter 渲染的
        // 编号列表（如 "1. 宽窄巷子（文化·…）"），原 check 只能解析 JSON 数组，
        // 导致观测静默跳过（groundingRate 从未落库）。
        AttractionGroundingChecker.GroundingReport report =
                checker.checkText(candidates, attractionsText);
        if (report == null || !report.checked()) {
            return;
        }
        TraceContext.Holder holder = TraceContext.current();
        holder.groundingRate = report.rate();
        holder.groundingUnmatched = JsonUtils.toJson(report.unmatchedNames());
        log.info("[Grounding] rate={} ({}/{}), unmatched={}",
                String.format("%.2f", report.rate()), report.matched(), report.total(),
                report.unmatchedNames());
    }

    /**
     * M8-6：REFINE 保留性校验（观测）——原行程景点集 vs 新输出景点集，静默丢失率写 trace。
     *
     * <p>原行程名从会话知识 itinerary_day 切片提取，新输出名从回答【推荐景点】段提取；
     * 结果仅记录（retentionRate + lost），为未来升级为完整调解 loop 提供数据依据。</p>
     */
    public static void recordRetention(AttractionGroundingChecker checker,
                                       List<Map<String, Object>> sessionHits,
                                       String response) {
        if (checker == null || !TraceContext.active()
                || sessionHits == null || sessionHits.isEmpty()) {
            return;
        }
        Set<String> previous = extractPreviousAttractionNames(sessionHits);
        if (previous.isEmpty()) {
            return;
        }
        String attractionsText = extractSection(response, "推荐景点");
        if (attractionsText == null) {
            attractionsText = response;
        }
        List<String> extracted = checker.extractAttractionNames(attractionsText);
        Set<String> newNames = extracted == null ? Set.of() : new LinkedHashSet<>(extracted);
        AttractionGroundingChecker.RetentionReport report =
                checker.checkRetention(previous, newNames);
        if (report == null) {
            return;
        }
        TraceContext.Holder holder = TraceContext.current();
        holder.retentionRate = report.rate();
        holder.retentionLost = JsonUtils.toJson(report.lostNames());
        log.info("[Retention] rate={} ({}/{}), lost={}",
                String.format("%.2f", report.rate()), report.kept(), report.total(),
                report.lostNames());
    }

    /** 从会话知识切片（type=itinerary_day 的 content）提取原行程景点名 */
    private static Set<String> extractPreviousAttractionNames(
            List<Map<String, Object>> sessionHits) {
        Set<String> names = new LinkedHashSet<>();
        for (Map<String, Object> hit : sessionHits) {
            if (!"itinerary_day".equals(String.valueOf(hit.getOrDefault("type", "")))) {
                continue;
            }
            Object content = hit.get("content");
            if (content != null) {
                names.addAll(AttractionGroundingChecker.extractAttractionNames(
                        String.valueOf(content)));
            }
        }
        return names;
    }

    /** 从组合输入中提取候选景点名（【知识库检索候选景点】标记后的 JSON 数组） */
    private static Set<String> extractCandidateNames(String composed) {
        if (composed == null || composed.isBlank()) {
            return Set.of();
        }
        int marker = composed.indexOf("【知识库检索候选景点】");
        String segment = marker >= 0 ? composed.substring(marker) : composed;
        String json = AttractionGroundingChecker.extractJsonArray(segment);
        if (json == null) {
            return Set.of();
        }
        try {
            List<?> list = JsonUtils.fromJson(json, List.class);
            if (list == null) {
                return Set.of();
            }
            Set<String> names = new LinkedHashSet<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.get("name") != null) {
                    names.add(String.valueOf(m.get("name")).trim());
                }
            }
            return names;
        } catch (Exception e) {
            return Set.of();
        }
    }

    /** 提取最终回答中的指定章节（【xxx】之后到下一个章节标记之前） */
    private static String extractSection(String text, String title) {
        if (text == null) {
            return null;
        }
        String marker = "【" + title + "】";
        int start = text.indexOf(marker);
        if (start < 0) {
            return null;
        }
        int contentStart = start + marker.length();
        int nextSection = text.indexOf("【", contentStart);
        return nextSection > contentStart
                ? text.substring(contentStart, nextSection).trim()
                : text.substring(contentStart).trim();
    }
}
