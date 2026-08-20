package com.travel.common.file;

import java.io.InputStream;

/**
 * 文件存储端口（F104 P1）：上传/URL/删除。实现（MinIO）与调用方解耦，
 * 未来可换 OSS/COS 仅替换实现。
 */
public interface FileStoragePort {

    /** 上传并返回公开访问 URL */
    String upload(InputStream in, long size, String contentType, String bucket, String object);

    String presignedGetUrl(String bucket, String object);

    /** 流式读取对象（不存在抛 NoSuchKey 异常；调用方负责关闭流） */
    InputStream read(String bucket, String object);

    void delete(String bucket, String object);
}
