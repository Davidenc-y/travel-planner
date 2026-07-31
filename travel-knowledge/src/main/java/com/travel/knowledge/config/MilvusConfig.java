package com.travel.knowledge.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Milvus 向量数据库配置
 *
 * <p>适配 interview-memory MilvusConfig，改动：</p>
 * <ul>
 *   <li>包名 com.interview.memory.config → com.travel.knowledge.config</li>
 *   <li>Collection 名 interview_memory → attraction_vectors</li>
 *   <li>配置前缀 interview.memory.milvus → milvus</li>
 * </ul>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Configuration
public class MilvusConfig {

    private static final String COLLECTION = "attraction_vectors";

    @Value("${milvus.host:localhost}")
    private String host;

    @Value("${milvus.port:19530}")
    private int port;

    @Bean
    public MilvusServiceClient milvusClient() {
        var param = ConnectParam.newBuilder()
                .withHost(host).withPort(port).build();
        log.info("初始化 Milvus 客户端: {}:{}", host, port);
        return new MilvusServiceClient(param);
    }

    /**
     * 启动时检查 Collection 是否存在（不自动创建，由 init_milvus.py 脚本创建）
     */
    @PostConstruct
    public void checkCollection() {
        try {
            var client = milvusClient();
            var resp = client.hasCollection(
                    io.milvus.param.collection.HasCollectionParam.newBuilder()
                            .withCollectionName(COLLECTION).build());
            boolean exists = Boolean.TRUE.equals(resp.getData());
            if (exists) {
                log.info("Milvus Collection {} 就绪", COLLECTION);
            } else {
                log.warn("Milvus Collection {} 不存在，请运行 scripts/init_milvus.py 创建", COLLECTION);
            }
        } catch (Exception e) {
            log.warn("Milvus 连接失败（服务仍可启动）: {}", e.getMessage());
        }
    }
}
