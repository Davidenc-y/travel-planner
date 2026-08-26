package com.travel.webflux;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;

/**
 * M6-32：WebFlux 试点应用（端口 8083，已接入真实聊天领域）。
 *
 * <p>扫描 com.travel.planning（travel-chat-domain + travel-chat-stream 同包类），
 * 由 ChatService 提供真实 ChatStreamExecutor（Pilot 条件 Bean 自动让位）；
 * 不扫描 travel-common，TokenAuthService/StreamMetrics 由
 * {@code StreamBeansConfig} 显式提供；@MapperScan 覆盖领域仓储（@Mapper 接口）；
 * @EnableFeignClients 覆盖 KnowledgeClient（travel-chat-domain）。</p>
 */
@SpringBootApplication(scanBasePackages = {
        "com.travel.webflux",
        "com.travel.planning"})
@MapperScan("com.travel.planning.repository")
@EnableFeignClients(basePackages = "com.travel.planning.client")
// M6-33：MyBatis-Plus 分页 + createdAt/updatedAt 自动填充（planning 经
// com.travel.common 扫描获得；WebFlux 不扫描 common，需显式导入）
@Import(com.travel.common.config.MybatisPlusConfig.class)
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
