package com.travel.gateway;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * V-3：独立流式网关（架构演进）——纯传输层。
 *
 * <p>职责边界（02 方案 §〇 V-3）：JWT 验证 + SSE 消费转发 + 限流；
 * <b>禁嵌入聊天域</b>（旧 webflux 整域复制的教训）——聊天业务全在 planning 进程（8081），
 * 网关仅经 {@code WebClient} 消费其 SSE 端点并桥接为 Reactive {@code Flux<ServerSentEvent>}。</p>
 *
 * <p>扫描范围仅 com.travel.gateway + com.travel.common（横切组件 TokenAuthService/
 * GrayFlags/RateLimiter），不扫任何聊天域包。</p>
 */
@SpringBootApplication(scanBasePackages = "com.travel.gateway")
// 审计修复：common 全包扫描拉入 servlet-dependent GlobalExceptionHandler（WebFlux 无 servlet）——
// 改为仅扫 gateway 包 + 显式 @Import 需要的 common bean
@org.springframework.context.annotation.Import({
    com.travel.common.auth.TokenAuthService.class
})
public class TravelStreamGatewayApplication {

    public static void main(String[] args) {
        // gateway.yml 为本模块唯一配置源（V 提示词：yml 新键仅限 gateway.yml）
        new SpringApplicationBuilder(TravelStreamGatewayApplication.class)
                .properties("spring.config.name=gateway")
                .run(args);
    }
}
