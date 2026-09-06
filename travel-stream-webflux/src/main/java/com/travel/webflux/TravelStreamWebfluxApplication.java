package com.travel.webflux;

import com.travel.planning.ChatDomainMarker;
import com.travel.planning.stream.ChatStreamMarker;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;

/**
 * M6-32：WebFlux 试点应用（端口 8083，已接入真实聊天领域）。
 *
 * <p>装配 chat-domain + chat-stream（由 ChatService 提供真实 ChatStreamExecutor，
 * Pilot 条件 Bean 自动让位）；不扫描 travel-common，TokenAuthService/StreamMetrics
 * 由 {@code StreamBeansConfig} 显式提供；@MapperScan 覆盖领域仓储（@Mapper 接口）；
 * @EnableFeignClients 覆盖 KnowledgeClient（travel-chat-domain）。</p>
 *
 * <p>M16-4 装配显式化：scanBasePackages 字符串改为 marker 类锚点
 * （basePackageClasses），扫描范围与原 {"com.travel.webflux","com.travel.planning"}
 * 完全一致（ChatDomainMarker 同根包跨 jar 合并语义不变）；chat-domain 域配置
 * 单源见 classpath:application-chat.yml；启动后见 [ChatAssembly] 装配清单日志。</p>
 */
@SpringBootApplication(scanBasePackageClasses = {
        WebfluxModuleMarker.class,  // travel-stream-webflux 本模块
        ChatDomainMarker.class,     // travel-chat-domain（com.travel.planning，跨 jar）
        ChatStreamMarker.class})    // travel-chat-stream（com.travel.planning.stream）
@MapperScan("com.travel.planning.repository")
@EnableFeignClients(basePackages = "com.travel.planning.client")
// M6-33：MyBatis-Plus 分页 + createdAt/updatedAt 自动填充（planning 经
// com.travel.common 扫描获得；WebFlux 不扫描 common，需显式导入）
// M7：模型网关装配（travel.ai.model-registry.enabled=true 时提供 chatModel/lightModel）
@Import({com.travel.common.config.MybatisPlusConfig.class,
        com.travel.aigateway.config.GatewayAutoConfig.class})
public class TravelStreamWebfluxApplication {

    public static void main(String[] args) {
        SpringApplication.run(TravelStreamWebfluxApplication.class, args);
        System.out.println("""
                ===================================================
                  Travel Stream WebFlux Pilot Started (port 8083)
                ===================================================
                """);
    }
}
