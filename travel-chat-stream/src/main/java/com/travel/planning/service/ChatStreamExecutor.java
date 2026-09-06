package com.travel.planning.service;

import com.travel.core.stream.TurnGate;

/**
 * M6-6-R1 Step 0：聊天流执行端口——由 travel-planning 的 {@code ChatService}
 * 实现，使中立流模块不依赖业务模块。
 */
public interface ChatStreamExecutor {

    ChatStreamPrepared prepareStream(Long userId, String sessionId, String message,
                                     String clientMessageId, String model);

    /** M23（E1）：携带消息内锚定快照的六参准备（旧五参 default 委托，双栈/既有实现零破坏）。 */
    default ChatStreamPrepared prepareStream(Long userId, String sessionId, String message,
                                             String clientMessageId, String model,
                                             java.util.List<Long> anchorIds) {
        return prepareStream(userId, sessionId, message, clientMessageId, model);
    }

    /** M23b（E4）：携带偏好标签的七参准备（六参 default 委托，preferences=null 语义不变）。 */
    default ChatStreamPrepared prepareStream(Long userId, String sessionId, String message,
                                             String clientMessageId, String model,
                                             java.util.List<Long> anchorIds,
                                             com.travel.common.dto.PreferenceTagsDTO preferences) {
        return prepareStream(userId, sessionId, message, clientMessageId, model, anchorIds);
    }

    ChatStreamResult runStream(ChatStreamPrepared prepared, ChatProgressListener listener);

    /** 流式准备产物（gate 供流式路径复用，避免幂等门禁重复执行） */
    record ChatStreamPrepared(String sessionId, String message, Long userId,
                              String clientMessageId, TurnGate gate,
                              String sessionTitle, String model,
                              java.util.List<Long> anchorIds,
                              com.travel.common.dto.PreferenceTagsDTO preferences) {
        /** M23 前的七参兼容构造（锚定空集/无偏好），既有测试/调用点零改动。 */
        public ChatStreamPrepared(String sessionId, String message, Long userId,
                                  String clientMessageId, TurnGate gate,
                                  String sessionTitle, String model) {
            this(sessionId, message, userId, clientMessageId, gate, sessionTitle, model,
                    java.util.List.of(), null);
        }

        /** M23 的八参兼容构造（无偏好）。 */
        public ChatStreamPrepared(String sessionId, String message, Long userId,
                                  String clientMessageId, TurnGate gate,
                                  String sessionTitle, String model,
                                  java.util.List<Long> anchorIds) {
            this(sessionId, message, userId, clientMessageId, gate, sessionTitle, model,
                    anchorIds, null);
        }

        public boolean replay() {
            return !gate.proceed();
        }
    }

    /** 流式执行结果 */
    record ChatStreamResult(String response, long aiTokens, boolean fallback,
                            Long assistantMessageId, String sessionTitle,
                            AnchorSuggestion suggestion) {

        /** M23 前的五参兼容构造（suggestion=null），既有调用点零改动。 */
        public ChatStreamResult(String response, long aiTokens, boolean fallback,
                                Long assistantMessageId, String sessionTitle) {
            this(response, aiTokens, fallback, assistantMessageId, sessionTitle, null);
        }

        /** M23（P-D）：done.suggestion 载荷（null=done.data 不追加该键，旧契约字节不变）。 */
        public record AnchorSuggestion(String type, Long itineraryId, String title) {
        }
    }
}
