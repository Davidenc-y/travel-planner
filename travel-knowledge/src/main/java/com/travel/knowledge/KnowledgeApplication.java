package com.travel.knowledge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
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
// B3.4：chat-stream 包名归位后，旧扫描串 "com.travel.planning.stream" 仅覆盖适配包；
// 新根包 com.travel.stream 递归含 service 契约子包（ChatStreamService 依赖 chat-domain 实现，knowledge 无此上下文），
// 故改为显式 @Import 三个适配 bean，与旧装配语义严格等价（旧串实际装配的正是这三个 @Component）。
@SpringBootApplication(scanBasePackages = {
        "com.travel.knowledge", "com.travel.common"})
@EnableScheduling
// M7 Batch 4：模型网关装配（travel.ai.model-registry.enabled=true 时提供 chatModel/lightModel）
@Import({com.travel.aigateway.config.GatewayAutoConfig.class,
        com.travel.stream.SseStreamAdapter.class,
        com.travel.stream.StreamPayloadMapper.class,
        com.travel.stream.ChatStreamProperties.class})
public class KnowledgeApplication {

    public static void main(String[] args) {
        // M15-3：api.tavily.com 在本机 IPv6 路径 TLS 握手被远端重置，
        // 强制 IPv4 优先后再初始化 Spring（须在任何 java.net 网络初始化之前）。
        System.setProperty("java.net.preferIPv4Stack", "true");
        SpringApplication.run(KnowledgeApplication.class, args);
        System.out.println("""
                ===================================================
                  Travel Knowledge Service Started (port 8082)
                  知识库服务启动完成
                ===================================================
                """);
    }
}
