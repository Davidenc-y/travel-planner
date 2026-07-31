package com.travel.knowledge.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elasticsearch 客户端配置
 *
 * <p>直接复用 interview-memory ElasticsearchConfig，仅改包名。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Configuration
@SuppressWarnings("deprecation")
public class ElasticsearchConfig {

    @Value("${spring.elasticsearch.uris:http://localhost:9200}")
    private String esUri;

    @Bean(destroyMethod = "close")
    public RestHighLevelClient elasticsearchClient() {
        log.info("初始化 Elasticsearch 客户端: {}", esUri);
        return new RestHighLevelClient(
                RestClient.builder(HttpHost.create(esUri))
        );
    }
}
