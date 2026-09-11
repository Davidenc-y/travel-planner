package com.travel.planning.memory.pipeline;

import com.travel.planning.memory.chat.ChatIntent;
import com.travel.planning.memory.chat.ChatIntentClassifier;
import com.travel.planning.trace.TraceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * M3-14：MessagePipeline 步骤 5「意图」。
 * 入口意图分类 + 追溯上下文填充从 ChatService 抽出为独立可测步骤。
 */
@Component
@RequiredArgsConstructor
public class ChatIntentStep implements ChatPipelineStep {

    /** B3.2：步骤顺序——M3-14 步骤 5「意图」（依据 R7-pipeline-mapping 现发送链步骤序 5，ChatService :480）。 */
    static final int STEP_ORDER = 5;

    @Override
    public int order() {
        return STEP_ORDER;
    }

    private final ChatIntentClassifier chatIntentClassifier;

    /**
     * 意图分类（F85 第二步，开关关闭时回退 PLANNING），并在追溯上下文激活时填充
     * user/session/意图路径（F89）；返回意图供后续路由使用。
     */
    public ChatIntent classify(String sessionId, Long userId, String message) {
        ChatIntent intent = chatIntentClassifier.classify(message);
        if (TraceContext.active()) {
            TraceContext.Holder h = TraceContext.current();
            h.trace.setUserId(userId);
            h.trace.setSessionId(sessionId);
            h.addPath(intent.name().toLowerCase());
        }
        return intent;
    }
}
