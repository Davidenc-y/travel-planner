package com.travel.planning.config;

import com.travel.common.file.FileStoragePort;
import com.travel.common.file.FileStorageProperties;
import com.travel.common.file.MinioClientFactory;
import com.travel.common.file.MinioFileStorage;
import io.minio.MinioClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** MinIO 文件存储装配（F104 P1，头像） */
@Configuration
@EnableConfigurationProperties(FileStorageProperties.class)
public class PlanningFileConfig {

    @Bean
    public MinioClient planningMinioClient(FileStorageProperties props) {
        return MinioClientFactory.create(props);
    }

    @Bean
    public FileStoragePort planningFileStoragePort(MinioClient planningMinioClient,
                                                   FileStorageProperties props) {
        return new MinioFileStorage(planningMinioClient, props);
    }
}
