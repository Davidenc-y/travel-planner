package com.travel.gateway.service;

import com.travel.common.config.GrayFlags;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

/**
 * V-3c：planning 流式客户端——网关唯一出站通道。
 *
 * <p>纯传输层（P0⑬ 代码面）：SSE 事件按 {@code ServerSentEvent<String>} 全元数据透传
 * ——event 名/id/data 不解析不改造（planning 事件名 thinking/token/done/error 是前端
 * 协议，丢名即断链；方案字面 {@code Flux<String>} 仅取 data 会丢元数据，留痕偏离）；
 * 请求体按原始字符串透传（不反序列化），禁 import 任何 chat-stream/chat-domain 类型。</p>
 *
 * <p>断连取消：浏览器侧取消沿 Reactor 链传播至 WebClient 底层连接关闭，planning
 * SseEmitter 感知断连后走其原生清理，网关无需额外注册回调。</p>
 */
@Component
public class PlanningStreamClient {

    /** SSE 全元数据消费类型（元素=String，仅透传 data 字符串，无 JSON 反序列化）。 */
    static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_STRING =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;
    private final long responseTimeoutMs;
    private final String internalToken;

    public PlanningStreamClient(
            @Value("${travel.gateway.planning-base-url:http://localhost:8081}") String planningBaseUrl,
            @Value("${travel.gateway.response-timeout-ms:330000}") long responseTimeoutMs,
            @Value("${travel.internal.token:}") String internalToken) {
        this.webClient = WebClient.builder().baseUrl(planningBaseUrl).build();
        this.responseTimeoutMs = responseTimeoutMs;
        this.internalToken = internalToken;
    }

    /**
     * 转发流：POST {base}/api/v1/chat/sessions/{sessionId}/messages/stream。
     *
     * <p>头组合实读实证（轮 125 前置核验）：planning stream 端点受 JwtAuthInterceptor
     * 保护（WebConfig 排除名单仅 writeback/brief/weather 桥，无此路径）——必须转发
     * 客户端 Authorization（网关与 planning 同 jwt.secret，两侧双重校验）；
     * X-Internal-Token 按方案注入（值取与 planning 同名键 {@code travel.internal.token}，
     * 供端点未来收敛内部校验时零改网关）。超时=元素间隔（planning 原生 15s keepalive
     * 持续喂流，静默 330s 判链路死亡）。</p>
     */
    public Flux<ServerSentEvent<String>> stream(String sessionId, String rawBody, String userAuthorization) {
        return webClient.post()
                .uri("/api/v1/chat/sessions/{sessionId}/messages/stream", sessionId)
                .headers(h -> h.addAll(forwardHeaders(userAuthorization, internalToken)))
                .bodyValue(rawBody)
                .retrieve()
                .bodyToFlux(SSE_STRING)
                .timeout(Duration.ofMillis(responseTimeoutMs));
    }

    /**
     * 转发头组合：Accept text/event-stream + Content-Type json + 客户端 Authorization
     * 转发 + X-Internal-Token（{@link GrayFlags#HEADER_INTERNAL_TOKEN}）注入。
     * 空 internalToken 不落头（不发假凭证）；包级可见供单测直连（V-1 范式）。
     */
    static HttpHeaders forwardHeaders(String userAuthorization, String internalToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (userAuthorization != null && !userAuthorization.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, userAuthorization);
        }
        if (internalToken != null && !internalToken.isBlank()) {
            headers.set(GrayFlags.HEADER_INTERNAL_TOKEN, internalToken);
        }
        return headers;
    }
}
