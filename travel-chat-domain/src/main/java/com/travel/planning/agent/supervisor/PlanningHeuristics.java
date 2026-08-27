package com.travel.planning.agent.supervisor;

/**
 * M6-57/T9 Step1：规划/回顾启发式判定词表（从 TravelSupervisorAgent 迁出）。
 *
 * <p>纯静态、零状态；供阻塞/图流/直答执行器共用，作为关键词表的单一来源之一
 * （与 ChatIntentClassifier 的六类意图词表语义互补，勿合并为同一表——见
 * M6-55 Batch 2 辩证结论）。</p>
 */
public final class PlanningHeuristics {

    private PlanningHeuristics() {
    }

    /**
     * F77/B4-2：疑似规划类请求（避免对画像查询/闲聊等非规划问题多花一次整图调用）。
     */
    public static boolean looksLikePlanningRequest(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return false;
        }
        String q = userInput;
        int idx = q.lastIndexOf("【当前问题】");
        if (idx >= 0) {
            q = q.substring(idx);
        }
        return q.contains("规划") || q.contains("行程") || q.contains("推荐")
                || q.contains("景点") || q.contains("日游") || q.contains("预算")
                || q.contains("攻略") || q.contains("哪里");
    }

    /**
     * F85：回顾类问题判定（事实型"上次/之前发生了什么"）。
     * 变更型（优化/调整/重新规划）必须返回 false——那是规划请求，不能早退。
     */
    public static boolean isRecallQuery(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return false;
        }
        String q = userInput;
        int idx = q.lastIndexOf("【当前问题】");
        if (idx >= 0) {
            q = q.substring(idx);
        }
        boolean recall = q.contains("上次") || q.contains("之前")
                || q.contains("回顾") || q.contains("安排了哪些")
                || q.contains("都去了") || q.contains("去过") || q.contains("行程记录");
        boolean change = q.contains("优化") || q.contains("调整") || q.contains("重新规划")
                || q.contains("改成") || q.contains("换") || q.contains("重新安排");
        return recall && !change;
    }

    /** 路由决策形如 ["agent"] / ["FINISH"] / [] / FINISH，不当作最终回答。 */
    public static boolean looksLikeRoutingDecision(String text) {
        String t = text == null ? "" : text.trim();
        if ("FINISH".equalsIgnoreCase(t) || "[]".equals(t)) {
            return true;
        }
        return t.startsWith("[") && t.endsWith("]");
    }
}
