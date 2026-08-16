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
     * 发送消息
     */
    @PostMapping("/sessions/{sessionId}/messages")
    public R<ChatResponseDTO> sendMessage(@PathVariable String sessionId,
                                           @RequestBody Map<String, String> body,
                                           @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        String message = body.get("message");
        return R.ok(chatService.sendMessage(sessionId, message, AuthUtils.resolveUserId(userId)));
    }
}
