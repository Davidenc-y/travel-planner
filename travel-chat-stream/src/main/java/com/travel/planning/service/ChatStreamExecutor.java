package com.travel.planning.service;

import com.travel.core.stream.TurnGate;

/**
 * M6-6-R1 Step 0：聊天流执行端口——由 travel-planning 的 {@code ChatService}
 * 实现，使中立流模块不依赖业务模块。
 */
public interface ChatStreamExecutor {

    ChatStreamPrepared prepareStream(Long userId, String sessionId, String message,
                                     String clientMessageId);

    ChatStreamResult runStream(ChatStreamPrepared prepared, ChatProgressListener listener);

    /** 流式准备产物（gate 供流式路径复用，避免幂等门禁重复执行） */
    record ChatStreamPrepared(String sessionId, String message, Long userId,
                              String clientMessageId, TurnGate gate,
                              String sessionTitle) {
        public boolean replay() {
            return !gate.proceed();
        }
    }

    /** 流式执行结果 */
    record ChatStreamResult(String response, long aiTokens, boolean fallback,
                            Long assistantMessageId, String sessionTitle) {
    }
}
