package com.travel.gateway.sentinel;

import com.alibaba.csp.sentinel.adapter.spring.webflux.callback.BlockRequestHandler;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * AM-1（GM-1，v1.1 修正 1）：Sentinel webflux 阻断响应自定义实现——替换破损的适配器默认
 * 处理器（sentinel-spring-webflux-adapter 1.8.9 对 spring-webflux 6.2.7 的 NoSuchMethodError）。
 * 统一 JSON 429 体（阻断发生在流开始之前，无已开始 SSE 流；与项目错误契约 40302/401 族同构）。
 * 零新依赖；API 签名常量池已实证（handleRequest(ServerWebExchange,Throwable)→Mono&lt;ServerResponse&gt;）。
 */
public class FriendlyBlockRequestHandler implements BlockRequestHandler {
    @Override
    public Mono<ServerResponse> handleRequest(ServerWebExchange exchange, Throwable t) {
        return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(
                        "{\"code\":42901,\"message\":\"当前访问人数较多，请稍后重试\",\"success\":false}"));
    }
}
