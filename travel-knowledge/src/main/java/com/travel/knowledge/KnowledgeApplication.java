package com.travel.knowledge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 知识库服务启动类
 *
 * <p>核心模块：RAG 检索 + ETL 管道 + 景点数据管理</p>
 *
 * <p>端口：8082</p>
 *
 * @author david_ency
 * @version 1.0-SNAPSHOT
 * @since 2026-07-28
 */
@SpringBootApplication(scanBasePackages = {
        "com.travel.knowledge", "com.travel.common", "com.travel.webmvc",
        "com.travel.planning.stream"})
@EnableScheduling
public class KnowledgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(KnowledgeApplication.class, args);
        System.out.println("""
                ===================================================
                  Travel Knowledge Service Started (port 8082)
                  知识库服务启动完成
                ===================================================
                """);
    }
}
