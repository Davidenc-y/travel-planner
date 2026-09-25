package com.travel.planning.service;

import com.travel.common.entity.ChatMessage;
import com.travel.common.entity.ChatMessageIdem;
import com.travel.common.entity.ChatSession;
import com.travel.common.exception.BusinessException;
import com.travel.common.exception.ErrorCode;
import com.travel.memory.sessionstore.SessionStorePort;
import com.travel.planning.cancellation.TurnCancellationBroadcaster;
import com.travel.planning.memory.pipeline.ChatBreakpointStore;
import com.travel.planning.memory.pipeline.ChatPersistenceStep;
import com.travel.stream.service.TurnCancellationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Z-4a：轮次生命周期服务（自 ChatService 拆出——中断/清除断点/状态查询/最近可恢复轮次）。
 *
 * <p>E-4 式逐字迁移：方法体与 {@code requireOwnedSession} 守卫自 ChatService 原文搬运
 * （会话守卫的本类副本=四方法仅用作所有权校验、session 变量在体内未使用）；两个
 * 结果 record（{@link ChatService.TurnStatusResult} / {@link ChatService.LatestInterruptedTurn}）
 * 留守 ChatService（公共 API FQN 零变+既有测试引用面）；ChatService 保留同名委托方法
 * （公共 API 签名零变）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TurnLifecycleService {

    private final SessionStorePort sessionStorePort;
    private final ChatPersistenceStep chatPersistenceStep;
    private final ChatBreakpointStore breakpointStore;
    private final TurnCancellationRegistry cancellationRegistry;
    private final TurnCancellationBroadcaster cancellationBroadcaster;

    /** T-5c：恢复注入开关（默认关=E-33 纯净；与 ChatService 同键同默认） */
    @Value("${travel.chat.supervisor.resume-ledger.enabled:false}")
    private boolean resumeLedgerEnabled = false;

    /** T-5c：子代理结果台账（optional 注入，null=零决策零注入） */
    private com.travel.planning.memory.knowledge.SupervisorResultLedger supervisorResultLedger;

    @Autowired(required = false)
    void setSupervisorResultLedger(
            com.travel.planning.memory.knowledge.SupervisorResultLedger supervisorResultLedger) {
        this.supervisorResultLedger = supervisorResultLedger;
    }

    /**
     * M6-36：中断在途轮次（PENDING → FAILED + Redis 中断标记）。
     *
     * <p>断点快照由 runStream 在路由前写入；若中断发生时尚未写入（步骤 3~7），
     * 重试将按 FAILED 语义整体重跑。</p>
     */
    public void interruptTurn(Long userId, String sessionId, String clientMessageId) {
        ChatSession session = requireOwnedSession(userId, sessionId);
        if (clientMessageId == null || clientMessageId.isBlank()) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED.code(),
                    ErrorCode.IDEMPOTENCY_KEY_REQUIRED.message());
        }
        boolean flipped = chatPersistenceStep.markTurnInterrupted(sessionId, clientMessageId);
        if (flipped) {
            breakpointStore.markInterrupted(clientMessageId);
            cancellationRegistry.cancel(clientMessageId);
            cancellationBroadcaster.publishCancel(sessionId, clientMessageId);
            log.info("[ChatInterrupt] 轮次已中断: sessionId={}, key={}", sessionId, clientMessageId);
        }
    }

    /**
     * M6-36：清除断点（用户发新消息时前端调用；prepareStream 侧另有双保险）。
     */
    public void clearBreakpoint(Long userId, String sessionId, String clientMessageId) {
        ChatSession session = requireOwnedSession(userId, sessionId);
        breakpointStore.clearBreakpoint(sessionId, clientMessageId);
        // T-5e：单清同步——断点与台账同生共死（开关开时）
        if (resumeLedgerEnabled && supervisorResultLedger != null) {
            supervisorResultLedger.clear(sessionId, clientMessageId);
        }
        cancellationRegistry.cancel(clientMessageId);
        cancellationBroadcaster.publishCancel(sessionId, clientMessageId);
        log.info("[ChatInterrupt] 断点已清除: sessionId={}, key={}", sessionId, clientMessageId);
    }

    /**
     * M6-42：查询轮次状态（前端刷新后恢复重试入口）。
     *
     * <p>resumable = INTERRUPTED 且断点快照仍存在（Redis 30min TTL 窗口内）；
     * 新消息已把旧轮次置 FAILED，故返回 FAILED 时前端不显示重试。</p>
     */
    public ChatService.TurnStatusResult getTurnStatus(Long userId, String sessionId, String clientMessageId) {
        ChatSession session = requireOwnedSession(userId, sessionId);
        if (clientMessageId == null || clientMessageId.isBlank()) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED.code(),
                    ErrorCode.IDEMPOTENCY_KEY_REQUIRED.message());
        }
        ChatMessageIdem row = chatPersistenceStep.findTurn(sessionId, clientMessageId);
        if (row == null) {
            return new ChatService.TurnStatusResult(null, false, null);
        }
        boolean hasBreakpoint =
                !breakpointStore.loadBreakpoint(sessionId, clientMessageId).isEmpty();
        boolean resumable =
                ChatMessageIdem.STATUS_INTERRUPTED.equals(row.getStatus()) && hasBreakpoint;
        String userMessage = null;
        if (row.getUserMessageId() != null) {
            ChatMessage m = sessionStorePort.findMessageById(row.getUserMessageId());
            if (m != null) {
                userMessage = m.getContent();
            }
        }
        return new ChatService.TurnStatusResult(row.getStatus(), resumable, userMessage);
    }

    /**
     * M6-47：会话最近可恢复中断轮次（刷新/重进会话恢复重试入口）。
     *
     * <p>浏览器刷新/关闭页面时前端不会执行 handleStop（localStorage 无 key），
     * 因此刷新恢复不能依赖前端本地状态——由后端按"INTERRUPTED + 断点存在"
     * 权威查询，前端进入会话时直接获取。</p>
     */
    public ChatService.LatestInterruptedTurn getLatestInterruptedTurn(Long userId, String sessionId) {
        ChatSession session = requireOwnedSession(userId, sessionId);
        ChatMessageIdem row = chatPersistenceStep.findLatestInterrupted(sessionId);
        if (row == null) {
            return new ChatService.LatestInterruptedTurn(null, null, false);
        }
        boolean hasBreakpoint =
                !breakpointStore.loadBreakpoint(sessionId, row.getClientMessageId()).isEmpty();
        String userMessage = null;
        if (row.getUserMessageId() != null) {
            ChatMessage m = sessionStorePort.findMessageById(row.getUserMessageId());
            if (m != null) {
                userMessage = m.getContent();
            }
        }
        return new ChatService.LatestInterruptedTurn(
                row.getClientMessageId(), userMessage, hasBreakpoint && userMessage != null);
    }

    private ChatSession requireOwnedSession(Long userId, String sessionId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED.code(), ErrorCode.UNAUTHORIZED.message());
        }
        ChatSession session = chatPersistenceStep.requireSession(sessionId);
        if (!userId.equals(session.getUserId())) {
            throw new BusinessException(ErrorCode.SESSION_ACCESS_DENIED.code(),
                    ErrorCode.SESSION_ACCESS_DENIED.message());
        }
        return session;
    }
}
