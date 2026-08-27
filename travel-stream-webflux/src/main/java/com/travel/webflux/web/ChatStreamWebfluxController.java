package com.travel.webflux.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.travel.common.exception.BusinessException;
import com.travel.core.stream.StreamEvent;
import com.travel.core.stream.StreamMeta;
import com.travel.core.stream.StreamPreflight;
import com.travel.core.stream.StreamRequest;
import com.travel.planning.service.ChatStreamService;
import com.travel.planning.stream.ChatStreamProperties;
import com.travel.planning.stream.StreamPayloadMapper;
import com.travel.webflux.security.ReactiveJwtAuthFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Map;

/**
 * M6-30：WebFlux 聊天流式端点（对应 MVC ChatController.streamMessage）。
 *
 * <p>路径/请求体/事件协议与 MVC 完全一致：POST
 * /api/v1/chat/sessions/{sessionId}/messages/stream；身份优先 exchange
 * attribute（ReactiveJwtAuthFilter 注入），其次 X-User-Id，缺失 40101；
 * 同步门禁失败直接抛 BusinessException（由全局异常映射 HTTP 状态）；
 * 成功返回 {@code Flux<ServerSentEvent<String>>}，data 由共享
 * StreamPayloadMapper 序列化，保证与 MVC SseStreamAdapter 逐字节一致。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/chat/sessions/{sessionId}/messages/stream")
public class ChatStreamWebfluxController {

    private final ChatStreamService chatStreamService;
    private final StreamPayloadMapper payloadMapper;
    private final ChatStreamProperties props;

    @PostMapping
    public Flux<ServerSentEvent<String>> stream(
            @PathVariable String sessionId,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            ServerWebExchange exchange) {
        if (!props.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "流式端点未启用");
        }
        Long userId = resolveUserId(exchange);
        String message = body.get("message");
        String clientMessageId = body.get("clientMessageId");
        StreamRequest request = new StreamRequest("chat", userId, sessionId,
                message, clientMessageId, Map.of(), lastEventId);
        StreamPreflight preflight = chatStreamService.preflight(request);
        if (!preflight.ok()) {
            throw new BusinessException(preflight.code(), preflight.message());
        }
        // share()：保活流与业务流共享同一次上游订阅，避免流水线重复执行
        Flux<StreamEvent> shared = chatStreamService.stream(request, preflight).share();
        Flux<ServerSentEvent<String>> heartbeats = Flux.interval(
                        Duration.ofMillis(Math.max(1, props.getKeepaliveMs())))
                .map(i -> toSse(StreamEvent.ping(
                        new StreamMeta("", "", "", "chat", null, false))))
                .takeUntilOther(shared.then().onErrorComplete());
        return Flux.merge(shared.map(this::toSse), heartbeats)
                .timeout(Duration.ofMillis(props.getTimeoutMs()), Flux.empty())
                .onErrorResume(e -> {
                    log.warn("[WebFluxStream] 流式异常: {}", e.getMessage());
                    return Flux.just(toSse(StreamEvent.error(
                            new StreamMeta("", sessionId, clientMessageId, "chat",
                                    50000, false), 50000,
                            e.getMessage() == null ? "流式处理失败" : e.getMessage())));
                });
    }

    /**
     * M6-56/T8：WebFlux 端点仅接受 JWT（ReactiveJwtAuthFilter 注入）——
     * 移除 X-User-Id 头回退，杜绝伪造头绕过鉴权（与旧 P0-4 同型风险）。
     * 前端灰度路径（NEXT_PUBLIC_STREAM_BASE=8083）的 SSE 请求已携带
     * Authorization: Bearer（api.ts sendMessageStream 实证）。
     */
    private Long resolveUserId(ServerWebExchange exchange) {
        Object attr = exchange.getAttributes().get(ReactiveJwtAuthFilter.ATTR_USER_ID);
        if (attr instanceof Long id && id > 0) {
            return id;
        }
        throw new BusinessException(40101, "用户未登录");
    }

    private ServerSentEvent<String> toSse(StreamEvent event) {
        ServerSentEvent.Builder<String> builder = ServerSentEvent.builder(toJson(event))
                .event(event.type().name().toLowerCase());
        if (event.eventId() != null) {
            builder.id(event.eventId());
        }
        return builder.build();
    }

    private String toJson(StreamEvent event) {
        try {
            return payloadMapper.toJson(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("SSE payload 序列化失败", e);
        }
    }
}
