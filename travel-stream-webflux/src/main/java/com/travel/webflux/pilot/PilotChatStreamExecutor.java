package com.travel.webflux.pilot;

import com.travel.common.exception.BusinessException;
import com.travel.core.stream.TurnGate;
import com.travel.planning.service.ChatProgressListener;
import com.travel.planning.service.ChatStreamExecutor;

/**
 * M6-30：WebFlux 试点演示执行器。
 *
 * <p>确定性强、零 LLM、零 DB：仅输出 thinking 阶段与分块回复，用于验证
 * 中立管线端口注入链路与 SSE 事件协议。真实域下沉（ChatService 迁入中立模块）
 * 后，本执行器由 {@code StreamBeansConfig} 的条件 Bean 自动让位，无需改动控制器。</p>
 */
public class PilotChatStreamExecutor implements ChatStreamExecutor {

    @Override
    public ChatStreamPrepared prepareStream(Long userId, String sessionId,
                                            String message, String clientMessageId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(40101, "用户未登录");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new BusinessException(40001, "会话ID不能为空");
        }
        if (message == null || message.isBlank()) {
            throw new BusinessException(40001, "消息不能为空");
        }
        return new ChatStreamPrepared(sessionId, message, userId,
                clientMessageId, TurnGate.fresh(), "WebFlux-Pilot");
    }

    @Override
    public ChatStreamResult runStream(ChatStreamPrepared prepared,
                                      ChatProgressListener listener) {
        ChatProgressListener l = listener == null ? ChatProgressListener.NOOP : listener;
        l.onThinking("preference", "正在分析您的偏好…（WebFlux Pilot）");
        l.onThinking("routing", "正在生成回答…（WebFlux Pilot）");
        String response = "【WebFlux 试点】已收到：" + prepared.message();
        l.onResponse(response);
        return new ChatStreamResult(response, 0L, false, -1L, "WebFlux-Pilot");
    }
}
