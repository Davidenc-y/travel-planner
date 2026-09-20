package com.travel.webflux.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.common.config.GrayFlags;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * RK-17：planning(8081) 内部桥接 WebClient 单点（原 WebfluxItineraryVersionPort /
 * WebfluxChatSupportBridge 双桥的 WebClient 构建段收敛）。
 *
 * <p>语义=两桥现状逐字：baseUrl=travel.planning-base-url（默认 8081）、
 * X-Internal-Token 条件默认头（internalToken 非空白才注入，M21-2 同源令牌）、
 * retrieve() 4xx/5xx 抛错由调用方 catch（fail-empty 静默降级语义保留在两桥方法体）；
 * 超时（15s/5s/8s）与 URI/解析差异由调用方 block(Duration) 决定，本类不 block。</p>
 */
@Component
public class InternalBridgeClient {

    private final WebClient webClient;
    private final String internalToken;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public InternalBridgeClient(
            @Value("${travel.planning-base-url:http://localhost:8081}") String planningBaseUrl,
            @Value("${travel.internal.token:}") String internalToken) {
        this(WebClient.builder().baseUrl(planningBaseUrl).build(), internalToken);
    }

    /** 包内直构入口（单测注入预置 WebClient） */
    InternalBridgeClient(WebClient webClient, String internalToken) {
        this.webClient = webClient;
        this.internalToken = internalToken;
    }

    /** POST JSON：头注入+retrieve，返回 Mono<String>（调用方 block(timeout) 与 catch） */
    public Mono<String> postJson(String uri, Object body) {
        return webClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(this::auth)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class);
    }

    /** GET：头注入+retrieve，返回 Mono<String>（调用方 block(timeout) 与 catch） */
    public Mono<String> getJson(String uri) {
        return webClient.get()
                .uri(uri)
                .headers(this::auth)
                .retrieve()
                .bodyToMono(String.class);
    }

    /** 两桥现状的 auth(h) 逐字：internalToken 非空白才注入 X-Internal-Token */
    private void auth(HttpHeaders h) {
        if (internalToken != null && !internalToken.isBlank()) {
            h.set(GrayFlags.HEADER_INTERNAL_TOKEN, internalToken);
        }
    }

    /** 共享 ObjectMapper（调用方解析 JSON 复用，不再各自 new） */
    public ObjectMapper objectMapper() {
        return objectMapper;
    }
}
