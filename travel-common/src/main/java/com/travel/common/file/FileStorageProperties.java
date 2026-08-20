package com.travel.common.file;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

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

    /** F121：对象访问模式（proxy=应用代理（默认）；presign=签名 URL；direct=公网直连） */
    private String accessMode = "proxy";

    /** F121：MinIO 公网可访问端点（direct/presign 模式时前端直连/签名使用；为空则回退 proxy） */
    private String publicEndpoint = "";

    /** F121：创建新桶时允许设置公开读的桶（仅景点图默认公开；avatars 保持私有） */
    private List<String> publicBuckets = new ArrayList<>(List.of("attractions"));
}
