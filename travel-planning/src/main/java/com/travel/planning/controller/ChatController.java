package com.travel.planning.controller;

import com.travel.common.dto.ChatResponseDTO;
import com.travel.common.entity.ChatMessage;
import com.travel.common.entity.ChatSession;
import com.travel.common.result.R;
import com.travel.planning.service.ChatService;
import com.travel.planning.util.AuthUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

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
}
