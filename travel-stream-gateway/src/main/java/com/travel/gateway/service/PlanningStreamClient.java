package com.travel.gateway.service;

import com.travel.common.config.GrayFlags;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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
@Slf4j
@Component
public class PlanningStreamClient {

    /** SSE 全元数据消费类型（元素=String，仅透传 data 字符串，无 JSON 反序列化）。 */
    static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_STRING =
            new ParameterizedTypeReference<>() {};

    /** W-1b：转发观测 Timer 名与请求关联 ID 头名。 */
    static final String TIMER_FORWARD = "gateway.stream.forward";
    static final String HEADER_REQUEST_ID = "X-Request-Id";
    static final String STREAM_URI = "/api/v1/chat/sessions/{sessionId}/messages/stream";

    private final WebClient webClient;
    private final long responseTimeoutMs;
    private final String internalToken;

    /** X-2c：服务名模式开关（空/空白=URL 模式现状零行为；单测直连未注入=null 同 URL 模式）。 */
    @Value("${travel.gateway.planning-service-name:}")
    private String planningServiceName;

    /** X-2c：@LoadBalanced 限定 builder（容器注入；独立构造/无该限定 Bean 时=null → 回落 URL 模式）。 */
    @Autowired(required = false)
    @LoadBalanced
    private WebClient.Builder loadBalancedBuilder;

    /** X-2c：service-name 模式惰性客户端（null=未构造，构造后缓存）。 */
    private volatile WebClient serviceModeClient;

    /** W-1b：转发时长 Timer（缺省自给 registry=无 MeterRegistry Bean 时零依赖可用；装配后切官方 registry）。 */
    private volatile MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private volatile Timer forwardTimer = newForwardTimer();

    @Autowired(required = false)
    void adoptMeterRegistry(MeterRegistry registry) {
        if (registry != null && registry != this.meterRegistry) {
            this.meterRegistry = registry;
            this.forwardTimer = newForwardTimer();
        }
    }

    private Timer newForwardTimer() {
        return Timer.builder(TIMER_FORWARD)
                .description("planning SSE 转发时长（W-1b）")
                .register(meterRegistry);
    }

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
     *
     * <p>W-1b：X-Request-Id 透传（客户端未带则生成 8 位短 UUID，见
     * {@link #forwardHeaders(String, String, String)}）；转发观测=Timer 记时长 +
     * {@code [GatewayForward]} 终态日志（ok/error/cancel），planning 侧零改动。</p>
     */
    public Flux<ServerSentEvent<String>> stream(String sessionId, String rawBody,
                                                String userAuthorization, String clientRequestId) {
        long startMs = System.currentTimeMillis();
        return resolveClient().post()
                .uri(STREAM_URI, sessionId)
                .headers(h -> h.addAll(forwardHeaders(userAuthorization, internalToken, clientRequestId)))
                .bodyValue(rawBody)
                .retrieve()
                .bodyToFlux(SSE_STRING)
                .timeout(Duration.ofMillis(responseTimeoutMs))
                .doFinally(sig -> {
                    long elapsedMs = System.currentTimeMillis() - startMs;
                    forwardTimer.record(elapsedMs, TimeUnit.MILLISECONDS);
                    log.info("[GatewayForward] uri={}, status={}, elapsedMs={}",
                            STREAM_URI, signalStatus(sig), elapsedMs);
                });
    }

    /**
     * X-2c：客户端解析——service-name 空/空白=现状 {@code webClient}（URL 模式构造逐字不动）；
     * 非空=经 @LoadBalanced builder 以 {@code http://{service-name}} 为基座（服务发现负载均衡，
     * 仅审计实弹开启）。builder 缺失时回落 URL 模式（live-fire 误配置保护）。
     * 包级可见供单测直连（V-1 范式）。
     */
    WebClient resolveClient() {
        if (planningServiceName == null || planningServiceName.isBlank()) {
            return webClient;
        }
        if (loadBalancedBuilder == null) {
            return webClient;
        }
        WebClient client = serviceModeClient;
        if (client == null) {
            client = loadBalancedBuilder.baseUrl("http://" + planningServiceName.trim()).build();
            serviceModeClient = client;
        }
        return client;
    }

    /** X-2c：负载均衡 builder 定义（嵌套配置随包扫描；service-name 空=此 Bean 惰性零行为）。 */
    @Configuration(proxyBeanMethods = false)
    static class LoadBalancedBuilderConfig {

        @Bean
        @LoadBalanced
        WebClient.Builder planningLoadBalancedBuilder() {
            return WebClient.builder();
        }
    }

    /** W-1b：doFinally 终态归一（ok/error/cancel；其余信号类型小写原名）。 */
    private static String signalStatus(SignalType sig) {
        if (sig == SignalType.ON_COMPLETE) {
            return "ok";
        }
        if (sig == SignalType.ON_ERROR) {
            return "error";
        }
        if (sig == SignalType.CANCEL) {
            return "cancel";
        }
        return sig.name().toLowerCase();
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

    /**
     * W-1b：转发头组合 + X-Request-Id 落头——客户端已带则逐字透传（端到端关联锚点），
     * 未带/空白则生成 8 位短 UUID（网关侧可关联回溯，planning 侧不读则无害）。
     * 包级可见供单测直连。
     */
    static HttpHeaders forwardHeaders(String userAuthorization, String internalToken, String clientRequestId) {
        HttpHeaders headers = forwardHeaders(userAuthorization, internalToken);
        if (clientRequestId != null && !clientRequestId.isBlank()) {
            headers.set(HEADER_REQUEST_ID, clientRequestId);
        } else {
            headers.set(HEADER_REQUEST_ID, UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        }
        return headers;
    }
}
