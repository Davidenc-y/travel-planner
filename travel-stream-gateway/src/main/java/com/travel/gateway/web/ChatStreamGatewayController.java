package com.travel.gateway.web;

import com.travel.gateway.security.ReactiveJwtAuthFilter;
import com.travel.gateway.service.PlanningStreamClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;

import java.time.Duration;

/**
 * V-3c：网关流式入口——POST /api/v1/chat/sessions/{sid}/messages/stream。
 *
 * <p>userId 取 V-3b 过滤器写入的 exchange attribute（缺位=过滤器未运行，防御性 401，
 * 正常路径过滤器已在入口前置拒绝）；请求体按原始字符串透传（不反序列化=P0⑬ 代码面）；
 * 响应 SSE 全元数据透传 + 心跳=planning 原生 keepalive 透传 + 元素间隔超时 + 断连取消
 * （下游取消传播至 WebClient 连接关闭）。</p>
 */
@RestController
@RequestMapping("/api/v1/chat")
public class ChatStreamGatewayController {

    private final PlanningStreamClient planningStreamClient;
    private final long responseTimeoutMs;

    public ChatStreamGatewayController(PlanningStreamClient planningStreamClient,
                                       @Value("${travel.gateway.response-timeout-ms:330000}") long responseTimeoutMs) {
        this.planningStreamClient = planningStreamClient;
        this.responseTimeoutMs = responseTimeoutMs;
    }

    @PostMapping("/sessions/{sessionId}/messages/stream")
    public ResponseEntity<Flux<ServerSentEvent<String>>> stream(
            @PathVariable String sessionId,
            @RequestBody String rawBody,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            ServerWebExchange exchange) {
        Long userId = exchange.getAttribute(ReactiveJwtAuthFilter.ATTR_USER_ID);
        if (userId == null || userId <= 0) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // 心跳=planning 原生 keepalive 透传（SseStreamAdapter 以 15s interval 合成进事件流，
        // 方案风险表"心跳与 planning 原生对齐"）；不叠加网关侧注入——merge+takeUntilOther
        // 双订阅结构对同步完成源存在竞态（单测①实证：other 先完成即取消主路零透传）。
        // 超时=元素间隔 330s（原生 15s 心跳持续喂流，静默 330s 判链路死亡）；
        // 断连取消=下游取消传播至 WebClient 连接关闭。
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(planningStreamClient
                        .stream(sessionId, rawBody, authorization)
                        .timeout(Duration.ofMillis(responseTimeoutMs)));
    }
}
