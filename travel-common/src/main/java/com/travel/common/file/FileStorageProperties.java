package com.travel.common.file;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MinIO 业务对象存储配置（F104 P1）：对应 yml {@code travel.minio.*}。
 * endpoint 形如 http://192.168.253.129:9000（业务 MinIO，见 docker-compose）。
 */
@Data
@ConfigurationProperties(prefix = "travel.minio")
public class FileStorageProperties {

    private String endpoint = "http://192.168.253.129:9000";

    private String accessKey = "minioadmin";

    private String secretKey = "minioadmin";

    /** 景点图片桶（公开读） */
    private String attractionsBucket = "attractions";

    /** 头像桶 */
    private String avatarsBucket = "avatars";
}
