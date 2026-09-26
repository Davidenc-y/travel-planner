package com.travel.planning.service;

import com.travel.common.auth.TokenAuthService;
import com.travel.common.entity.ChatMessageIdem;
import com.travel.common.exception.BusinessException;
import com.travel.common.exception.ErrorCode;
import com.travel.planning.memory.pipeline.ChatIdempotencyProperties;
import com.travel.planning.memory.pipeline.ChatPersistenceStep;
import com.travel.planning.repository.ChatMessageIdemMapper;
import com.travel.planning.util.AccessTokenBlacklistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * AA-2（T4）：轮次幂等预写服务——sendBeacon fire-and-forget 落占位行。
 *
 * <p>语义：仅写 {@link ChatMessageIdem} PENDING 行（既有表零 DDL），
 * {@code userMessageId} 刻意留空=预写占位标记；主链路 {@code beginTurn}
 * 识别占位行（PENDING+userMessageId NULL）后收编为本轮幂等行（补挂用户消息），
 * 真实在途行（PENDING+userMessageId 非空）仍按 40904 拒绝——既有端点语义零改。</p>
 *
 * <p>鉴权：sendBeacon 无法携带 Authorization 头——accessToken 随 body 自校验
 * （与 JwtAuthInterceptor 同一 TokenAuthService+黑名单链，拦截器面零触碰）；
 * 路径在 WebConfig excludePathPatterns 豁免（csp-report 先例）+本服务兜底校验。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatTurnPrewriteService {

    private final ChatMessageIdemMapper idemMapper;
    private final TokenAuthService tokenAuthService;
    private final AccessTokenBlacklistService blacklistService;
    private final ChatPersistenceStep chatPersistenceStep;
    private final ChatIdempotencyProperties idemProps;

    /**
     * 幂等预写：占位行已存在（含并发预写/主链路已登记）=幂等 no-op，
     * 绝不覆盖既有状态机状态；幂等开关关闭=no-op（与 beginTurn 同开关）。
     */
    public void prewrite(String clientMessageId, String sessionId, String accessToken) {
        if (clientMessageId == null || clientMessageId.isBlank()
                || sessionId == null || sessionId.isBlank()) {
            return;
        }
        if (!idemProps.isEnabled()) {
            return;
        }
        Long userId = requireAuthenticated(accessToken);
        // 会话存在性+所有权校验（requireSession 公开守卫复用，语义与 requireOwnedSession 对齐）
        if (!userId.equals(chatPersistenceStep.requireSession(sessionId).getUserId())) {
            throw new BusinessException(ErrorCode.SESSION_ACCESS_DENIED.code(),
                    ErrorCode.SESSION_ACCESS_DENIED.message());
        }
        if (idemMapper.selectById(clientMessageId) != null) {
            return;
        }
        ChatMessageIdem row = new ChatMessageIdem();
        row.setClientMessageId(clientMessageId);
        row.setSessionId(sessionId);
        row.setStatus(ChatMessageIdem.STATUS_PENDING);
        row.setUpdatedAt(java.time.LocalDateTime.now());
        try {
            idemMapper.insert(row);
            log.info("[TurnPrewrite] 占位行已预写: session={}, key={}", sessionId, clientMessageId);
        } catch (DuplicateKeyException e) {
            // 竞态兜底：主链路/并发预写先到——幂等 no-op
        }
    }

    private Long requireAuthenticated(String accessToken) {
        if (accessToken != null && tokenAuthService.validateAccessToken(accessToken)
                && !blacklistService.isRevoked(tokenAuthService.getJti(accessToken))) {
            Long userId = tokenAuthService.getUserIdFromToken(accessToken);
            if (userId != null && userId > 0) {
                return userId;
            }
        }
        throw new BusinessException(40101, "用户未登录");
    }
}
