package com.travel.knowledge.config;

import com.travel.common.file.FileStoragePort;
import com.travel.common.file.FileStorageProperties;
import com.travel.common.file.MinioClientFactory;
import com.travel.common.file.MinioFileStorage;
import io.minio.MinioClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** MinIO 文件存储装配（F104 P1，travel-common 复用） */
@Configuration
@EnableConfigurationProperties(FileStorageProperties.class)
public class KnowledgeFileConfig {

    @Bean
    public MinioClient minioClient(FileStorageProperties props) {
        return MinioClientFactory.create(props);
    }

    @Bean
    public FileStoragePort fileStoragePort(MinioClient minioClient, FileStorageProperties props) {
        return new MinioFileStorage(minioClient, props);
    }
}
