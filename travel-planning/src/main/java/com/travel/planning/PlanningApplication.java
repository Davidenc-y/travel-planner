package com.travel.planning;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 旅游行程规划服务启动类
 *
 * <p>核心模块：Agent 编排 + StateGraph 工作流 + 工具调用 + 行程 CRUD</p>
 *
 * <p>端口：8081</p>
 *
 * @author david_ency
 * @version 1.0-SNAPSHOT
 * @since 2026-07-28
 */
@SpringBootApplication(scanBasePackages = {"com.travel.planning", "com.travel.common"})
@EnableFeignClients(basePackages = "com.travel.planning.client")
@EnableScheduling
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
