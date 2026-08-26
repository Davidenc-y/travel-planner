package com.travel.core.stream;

/**
 * M6-6-R1 Step 0：聊天轮次幂等门禁结果（自 travel-planning ChatPersistenceStep 下沉，
 * 使中立流模块不依赖业务模块）。
 *
 * <p>语义（M4-3）：proceed=false + replayResponse 非空 → COMPLETED 重放；
 * proceed=true + userMessageAppended → 用户消息已在 beginTurn 事务内落库；
 * proceed=true + reuseUserMessage → FAILED 复用原用户消息。</p>
 */
public record TurnGate(
        boolean proceed,
        String replayResponse,
        Integer replayTokens,
        boolean reuseUserMessage,
        boolean userMessageAppended) {

    public static TurnGate fresh() {
        return new TurnGate(true, null, null, false, false);
    }

    /** 未命中：用户消息已在 beginTurn 事务内落库 */
    public static TurnGate freshAppended() {
        return new TurnGate(true, null, null, false, true);
    }

    public static TurnGate replay(String response, Integer tokens) {
        return new TurnGate(false, response, tokens, false, false);
    }

    public static TurnGate reuse() {
        return new TurnGate(true, null, null, true, false);
    }
}
