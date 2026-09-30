package com.travel.gateway.sentinel;

import com.alibaba.csp.sentinel.adapter.spring.webflux.callback.WebFluxCallbackManager;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

/**
 * AM-1（GM-1）注册装配：把 {@link FriendlyBlockRequestHandler} 挂到 Sentinel webflux 适配器的
 * 阻断回调位（{@code SentinelBlockExceptionHandler} 运行时经 {@code getBlockHandler().handleRequest}
 * 调用——1.8.9 常量池实证），替换破损默认实现（NoSuchMethodError→500 化）。
 *
 * <p>幂等：{@code setBlockHandler} 为静态字段赋值（后者覆盖），@PostConstruct 每容器执行一次，
 * 重复装配/重复 set 无副作用。窄上下文适配：零 bean 依赖（静态 manager + 直接实例化），
 * 仅装配本类的窄测试上下文无需软化即可工作（ObjectProvider 软化先例针对的缺 bean 失败形态在此不存在）。</p>
 */
@Configuration
public class SentinelWebFluxConfig {

    @PostConstruct
    public void registerFriendlyBlockHandler() {
        WebFluxCallbackManager.setBlockHandler(new FriendlyBlockRequestHandler());
    }
}
