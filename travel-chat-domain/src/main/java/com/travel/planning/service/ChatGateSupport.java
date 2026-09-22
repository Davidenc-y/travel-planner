package com.travel.planning.service;

import com.travel.common.entity.ChatSession;
import com.travel.common.enums.SessionStatus;
import com.travel.common.exception.BusinessException;
import com.travel.common.exception.ErrorCode;
import com.travel.core.stream.TurnGate;
import com.travel.aigateway.core.GatewayException;
import com.travel.aigateway.core.ModelRegistry;
import com.travel.planning.cancellation.TurnCancellationBroadcaster;
import com.travel.planning.memory.pipeline.ChatBreakpointStore;
import com.travel.planning.memory.pipeline.ChatPersistenceStep;
import com.travel.planning.memory.pipeline.ChatSessionGuardProperties;
import com.travel.stream.service.TurnCancellationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.List;

/**
 * MI-1：门禁装配（ARCHIVED 拒写 / 用户消息追加决策 / 旧断点清理+在途终止 / 请求级模型校验，
 * 方法体自 ChatService 逐字符迁出，语义与日志文案零变更；行内初始化保持既有直构签名不变）。
 */
@Slf4j
@Component
public class ChatGateSupport {

    /** M7 D6：未知/禁用/不可选模型 → 40005，不静默回退。 */
    public void validateModel(ModelRegistry modelRegistry, String model) {
        if (model == null || model.isBlank()) {
            return;
        }
        try {
            modelRegistry.requireSelectable(model);
        } catch (GatewayException e) {
            throw new BusinessException(ErrorCode.MODEL_NOT_FOUND.code(),
                    ErrorCode.MODEL_NOT_FOUND.message() + ": " + model);
        }
    }

    /**
     * M4-4/M4-3：轮次放行装配——ARCHIVED 会话拒绝新消息（40902；replay 已在调用方豁免）；
     * 无幂等键/开关关时由服务端追加用户消息（原路径）。
     */
    public void enforceTurnEntry(ChatSessionGuardProperties sessionGuardProps, ChatSession session,
            TurnGate gate, ChatPersistenceStep chatPersistenceStep, String sessionId, String message) {
        // M4-4：ARCHIVED 会话拒绝新消息（40902；replay 已在上面豁免）
        if (sessionGuardProps.isRejectArchived()
                && SessionStatus.ARCHIVED.name().equals(session.getStatus())) {
            throw new BusinessException(40902, "会话已关闭");
        }
        if (!gate.userMessageAppended() && !gate.reuseUserMessage()) {
            // 无幂等键/开关关：原路径由服务端追加用户消息
            chatPersistenceStep.appendUserMessage(sessionId, message);
        }
    }

    /**
     * M6-36/39：新轮次（非 FAILED 复用）清除同会话旧断点——旧任务的重试按钮随之失效；
     * 同时终止同会话其他在途轮次（防旧任务后台完成后幽灵落库）。
     */
    public void clearBreakpointsAndTerminate(TurnGate gate, ChatBreakpointStore breakpointStore,
            ChatPersistenceStep chatPersistenceStep, TurnCancellationRegistry cancellationRegistry,
            TurnCancellationBroadcaster cancellationBroadcaster, String sessionId, String clientMessageId,
            com.travel.planning.memory.knowledge.SupervisorResultLedger resumeLedger) {
        // M6-36：新轮次（非 FAILED 复用）清除同会话旧断点——旧任务的重试按钮随之失效
        if (!gate.reuseUserMessage()) {
            breakpointStore.clearSessionBreakpoints(sessionId);
            // T-5e：台账同生共死（新轮失效点对齐 M6-36 生命周期；null=开关关/未接线零变更）
            if (resumeLedger != null) {
                resumeLedger.clearSession(sessionId);
            }
            // M6-39：同时终止同会话其他在途轮次（防旧任务后台完成后幽灵落库）
            List<String> inFlight = chatPersistenceStep.markSessionInterrupted(
                    sessionId, clientMessageId);
            if (inFlight != null && !inFlight.isEmpty()) {
                inFlight.forEach(key -> {
                    breakpointStore.markInterrupted(key);
                    cancellationRegistry.cancel(key);
                    cancellationBroadcaster.publishCancel(sessionId, key);
                });
                log.info("[ChatInterrupt] 新消息终止在途轮次: sessionId={}, keys={}",
                        sessionId, inFlight);
            }
        }
    }
}
