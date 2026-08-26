package com.travel.planning.controller;

import com.travel.common.dto.ChatResponseDTO;
import com.travel.common.entity.ChatMessage;
import com.travel.common.entity.ChatSession;
import com.travel.common.result.R;
import com.travel.core.stream.StreamPreflight;
import com.travel.core.stream.StreamRequest;
import com.travel.planning.service.ChatStreamService;
import com.travel.planning.service.ChatService;
import com.travel.planning.service.TurnCancellationRegistry;
import com.travel.planning.stream.ChatStreamProperties;
import com.travel.planning.stream.StreamErrorMapper;
import com.travel.webmvc.stream.SseStreamAdapter;
import com.travel.planning.util.AuthUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 聊天接口
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ChatStreamService chatStreamService;
    private final SseStreamAdapter sseStreamAdapter;
    private final ChatStreamProperties chatStreamProps;
    // M6-40：SSE 断开/超时取消在途轮次
    private final TurnCancellationRegistry cancellationRegistry;

    /**
     * 创建会话
     */
    @PostMapping("/sessions")
    public R<String> createSession(@RequestBody Map<String, Object> body,
                                   @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        Long bodyUserId = body.get("userId") != null ? Long.valueOf(body.get("userId").toString()) : null;
        String title = body.get("title") != null ? body.get("title").toString() : null;
        // F68/B3-2：身份来源优先 accessToken（UserContextHolder），其次 body/头兜底
        return R.ok(chatService.createSession(AuthUtils.resolveUserId(bodyUserId != null ? bodyUserId : userId), title));
    }

    /**
     * 获取用户会话列表
     */
    @GetMapping("/sessions")
    public R<List<ChatSession>> listSessions(@RequestParam(required = false) Long userId) {
        return R.ok(chatService.listSessions(AuthUtils.resolveUserId(userId)));
    }

    /**
     * 获取会话历史
     */
    @GetMapping("/sessions/{sessionId}/history")
    public R<List<ChatMessage>> getHistory(@PathVariable String sessionId) {
        return R.ok(chatService.getHistory(sessionId));
    }

    /**
     * M4-4：关闭会话（显式触发：前端"结束会话"按钮；禁止 beforeunload 调用——刷新会误归档）。
     * 归档后同步尽力收口摘要，超时/失败由后台补偿兜底。
     */
    @PostMapping("/sessions/{sessionId}/close")
    public R<ChatService.CloseSessionResult> closeSession(@PathVariable String sessionId,
                                                           @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        return R.ok(chatService.closeSession(AuthUtils.resolveUserId(userId), sessionId));
    }

    /**
     * M5-1：更新会话标题（前端双击编辑保存；空/超长/越权由服务校验）
     */
    @PutMapping("/sessions/{sessionId}/title")
    public R<Void> updateTitle(@PathVariable String sessionId,
                               @RequestBody Map<String, String> body,
                               @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        chatService.updateTitle(AuthUtils.resolveUserId(userId), sessionId, body.get("title"));
        return R.ok();
    }

    /**
     * 发送消息（M4-3：body 可携带 clientMessageId 幂等键——超时重试携带同键可重放/防重复；
     * 不携带则走原路径）
     */
    @PostMapping("/sessions/{sessionId}/messages")
    public R<ChatResponseDTO> sendMessage(@PathVariable String sessionId,
                                           @RequestBody Map<String, String> body,
                                           @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        String message = body.get("message");
        String clientMessageId = body.get("clientMessageId");
        return R.ok(chatService.sendMessage(sessionId, message,
                AuthUtils.resolveUserId(userId), clientMessageId));
    }

    /**
     * M6：流式发送（SSE）。同步门禁（auth/guard/归属/幂等/归档）在返回 emitter 前完成，
     * 错误语义与 JSON 端点一致（40904/40302/40902 等走 HTTP）；流水线异步输出
     * thinking/token/done/error 事件。
     *
     * <p>注意：SseEmitter 必须作为控制器方法返回值直接返回，由 Spring 的
     * {@code ResponseBodyEmitterReturnValueHandler} 处理并自动设置
     * {@code text/event-stream}；<b>禁止</b>用 {@code ResponseEntity<SseEmitter>}
     * 包装——那会走 {@code HttpEntityMethodProcessor} 消息转换器，运行时抛
     * {@code HttpMessageNotWritableException: No converter for class SseEmitter}
     * （实测 2026-08-24：成都规划请求因此返回 500，前端回退 JSON 同键重试
     * 撞 PENDING 40904，最终显示兜底文案，而流水线实际已正常完成并落库）。</p>
     */
    @PostMapping("/sessions/{sessionId}/messages/stream")
    public Object streamMessage(@PathVariable String sessionId,
                                @RequestBody Map<String, String> body,
                                @RequestHeader(value = "X-User-Id", required = false) Long userIdHeader,
                                @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        if (!chatStreamProps.isEnabled()) {
            return ResponseEntity.notFound().build();
        }
        Long userId = AuthUtils.resolveUserId(userIdHeader);
        String message = body.get("message");
        String clientMessageId = body.get("clientMessageId");
        StreamRequest request = new StreamRequest("chat", userId, sessionId,
                message, clientMessageId, java.util.Map.of(), lastEventId);
        StreamPreflight preflight = chatStreamService.preflight(request);
        if (!preflight.ok()) {
            return ResponseEntity.status(StreamErrorMapper.httpStatus(preflight.code()))
                    .body(R.fail(preflight.code(), preflight.message()));
        }
        SseEmitter emitter = sseStreamAdapter.toEmitter(
                chatStreamService.stream(request, preflight),
                chatStreamProps.getTimeoutMs(),
                chatStreamProps.getKeepaliveMs(),
                () -> cancellationRegistry.cancel(clientMessageId));
        return emitter;
    }

    /**
     * M6-36：中断在途轮次（PENDING → FAILED + Redis 中断标记）。
     */
    @PostMapping("/sessions/{sessionId}/turns/{clientMessageId}/interrupt")
    public R<Void> interruptTurn(@PathVariable String sessionId,
                                 @PathVariable String clientMessageId,
                                 @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        chatService.interruptTurn(AuthUtils.resolveUserId(userId), sessionId, clientMessageId);
        return R.ok();
    }

    /**
     * M6-36：清除断点（用户发新消息时调用；prepareStream 侧另有双保险）。
     */
    @DeleteMapping("/sessions/{sessionId}/turns/{clientMessageId}/breakpoint")
    public R<Void> clearBreakpoint(@PathVariable String sessionId,
                                   @PathVariable String clientMessageId,
                                   @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        chatService.clearBreakpoint(AuthUtils.resolveUserId(userId), sessionId, clientMessageId);
        return R.ok();
    }

    /**
     * M6-42：查询轮次状态（前端刷新后恢复"执行已中断 + 重试"入口）。
     */
    @GetMapping("/sessions/{sessionId}/turns/{clientMessageId}")
    public R<ChatService.TurnStatusResult> getTurnStatus(
            @PathVariable String sessionId,
            @PathVariable String clientMessageId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        return R.ok(chatService.getTurnStatus(
                AuthUtils.resolveUserId(userId), sessionId, clientMessageId));
    }

    /**
     * M6-47：查询会话最近可恢复中断轮次（浏览器刷新/重进会话恢复重试入口）。
     */
    @GetMapping("/sessions/{sessionId}/interrupted-turn")
    public R<ChatService.LatestInterruptedTurn> getLatestInterruptedTurn(
            @PathVariable String sessionId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        return R.ok(chatService.getLatestInterruptedTurn(
                AuthUtils.resolveUserId(userId), sessionId));
    }
}
