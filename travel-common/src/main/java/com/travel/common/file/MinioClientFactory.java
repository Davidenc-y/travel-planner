package com.travel.common.file;

import io.minio.MinioClient;

/** MinioClient 工厂（复用：knowledge/planning 各自装配 Bean） */
public final class MinioClientFactory {

    private MinioClientFactory() {
    }

    public static MinioClient create(FileStorageProperties props) {
        return MinioClient.builder()
                .endpoint(props.getEndpoint())
                .credentials(props.getAccessKey(), props.getSecretKey())
                .build();
    }
}
