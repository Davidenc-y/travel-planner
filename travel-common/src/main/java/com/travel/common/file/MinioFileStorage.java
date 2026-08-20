package com.travel.common.file;

import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.SetBucketPolicyArgs;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/** MinIO 文件存储实现（F104 P1） */
@Slf4j
public class MinioFileStorage implements FileStoragePort {

    private final MinioClient client;
    private final FileStorageProperties props;

    public MinioFileStorage(MinioClient client, FileStorageProperties props) {
        this.client = client;
        this.props = props;
    }

    @Override
    public String upload(InputStream in, long size, String contentType, String bucket, String object) {
        try {
            ensureBucket(bucket);
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket).object(object)
                    .stream(in, size, -1)
                    .contentType(contentType)
                    .build());
            return String.format("%s/%s/%s", props.getEndpoint(), bucket, object);
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 上传失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String presignedGetUrl(String bucket, String object) {
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET).bucket(bucket).object(object)
                    .expiry(7, TimeUnit.DAYS).build());
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 预签名失败: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream read(String bucket, String object) {
        try {
            return client.getObject(GetObjectArgs.builder()
                    .bucket(bucket).object(object).build());
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 读取失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String bucket, String object) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(object).build());
        } catch (Exception e) {
            log.warn("[MinIO] 删除失败: {}/{} -> {}", bucket, object, e.getMessage());
        }
    }

    /** 桶不存在则创建；仅对 publicBuckets 配置内的桶设公开读（F121：avatars 保持私有） */
    private void ensureBucket(String bucket) throws Exception {
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            if (props.getPublicBuckets() != null && props.getPublicBuckets().contains(bucket)) {
                String policy = """
                        {"Version":"2012-10-17","Statement":[
                          {"Effect":"Allow","Principal":{"AWS":["*"]},
                           "Action":["s3:GetObject"],
                           "Resource":["arn:aws:s3:::%s/*"]}]}
                        """.formatted(bucket);
                client.setBucketPolicy(SetBucketPolicyArgs.builder().bucket(bucket).config(policy).build());
                log.info("[MinIO] 已创建桶并设公开读: {}", bucket);
            } else {
                log.info("[MinIO] 已创建私有桶: {}", bucket);
            }
        }
    }
}
