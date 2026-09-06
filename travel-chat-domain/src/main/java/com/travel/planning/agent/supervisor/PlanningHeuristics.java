package com.travel.planning.agent.supervisor;

import com.travel.planning.config.ChatWordLists;
import com.travel.planning.prompt.Markers;
import org.springframework.stereotype.Component;

import static com.travel.common.util.AgentOutputUtils.containsAny;

/**
 * M6-57/T9 Step1：规划/回顾启发式判定（从 TravelSupervisorAgent 迁出）。
 *
 * <p>M18-1 起词表单源（travel.chat.word-lists.heuristics），本类只保留判定算法；
 * 与 ChatIntentClassifier 的意图词表语义互补，勿合并为同一表——见 M6-55 Batch 2 辩证结论。</p>
 */
@Component
public class PlanningHeuristics {

    private final ChatWordLists wordLists;

    public PlanningHeuristics(ChatWordLists wordLists) {
        this.wordLists = wordLists;
    }

    /**
     * F77/B4-2：疑似规划类请求（避免对画像查询/闲聊等非规划问题多花一次整图调用）。
     */
    public boolean looksLikePlanningRequest(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return false;
        }
        String q = tailQuestion(userInput);
        return containsAny(q, wordLists.getHeuristics().getPlanning().toArray(new String[0]));
    }

    /**
     * F85：回顾类问题判定（事实型"上次/之前发生了什么"）。
     * 变更型（优化/调整/重新规划）必须返回 false——那是规划请求，不能早退。
     */
    public boolean isRecallQuery(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return false;
        }
        String q = tailQuestion(userInput);
        boolean recall = containsAny(q, wordLists.getHeuristics().getRecall().toArray(new String[0]));
        boolean change = containsAny(q, wordLists.getHeuristics().getChange().toArray(new String[0]));
        return recall && !change;
    }

    /** 取【当前问题】marker 之后的尾段（无 marker 用全文）。 */
    private static String tailQuestion(String userInput) {
        int idx = userInput.lastIndexOf(Markers.CURRENT_QUESTION);
        return idx >= 0 ? userInput.substring(idx) : userInput;
    }

    /** 路由决策形如 ["agent"] / ["FINISH"] / [] / FINISH，不当作最终回答。（无词表，保持静态） */
    public static boolean looksLikeRoutingDecision(String text) {
        String t = text == null ? "" : text.trim();
        if ("FINISH".equalsIgnoreCase(t) || "[]".equals(t)) {
            return true;
        }
        return t.startsWith("[") && t.endsWith("]");
    }
}
