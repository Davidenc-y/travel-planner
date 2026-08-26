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
public class ChatIntentStep {

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
