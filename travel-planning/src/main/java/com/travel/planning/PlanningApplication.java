package com.travel.planning;

import com.travel.common.CommonMarker;
import com.travel.stream.ChatStreamMarker;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 旅游行程规划服务启动类
 *
 * <p>核心模块：Agent 编排 + StateGraph 工作流 + 工具调用 + 行程 CRUD</p>
 *
 * <p>端口：8081</p>
 *
 * <p>M16-4 装配显式化：scanBasePackages 字符串通配改为 marker 类锚点
 * （basePackageClasses）——包重命名/迁移时编译失败而非 Bean 静默丢失。
 * 扫描范围与原 {"com.travel.planning","com.travel.common"} 完全一致（B3.0 摘除原
 * 第三锚点 com.travel.webmvc——该模块仅剩空标记接口，其横切组件已分别由
 * ChatStreamMarker/CommonMarker 覆盖；ChatDomainMarker 与 PlanningMarker 同根包，
 * 跨 jar 合并语义不变；chat-domain 域配置单源见 classpath:application-chat.yml）。</p>
 *
 * @author david_ency
 * @version 1.0-SNAPSHOT
 * @since 2026-07-28
 */
@SpringBootApplication(scanBasePackageClasses = {
        PlanningMarker.class,       // travel-planning 本模块
        ChatDomainMarker.class,     // travel-chat-domain（同根包 com.travel.planning，跨 jar）
        ChatStreamMarker.class,     // travel-chat-stream（com.travel.stream）
        CommonMarker.class})        // travel-common（TokenAuthService/MybatisPlusConfig/FileStorageProperties）
@EnableFeignClients(basePackages = "com.travel.planning.client")
@EnableScheduling
// M7：模型网关装配（travel.ai.model-registry.enabled=true 时提供 chatModel/lightModel）
@Import(com.travel.aigateway.config.GatewayAutoConfig.class)
public class PlanningApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlanningApplication.class, args);
        System.out.println("""
                ===================================================
                  Travel Planning Service Started (port 8081)
                  旅游行程规划服务启动完成
                ===================================================
                """);
    }
}
