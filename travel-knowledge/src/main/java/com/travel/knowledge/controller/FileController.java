package com.travel.knowledge.controller;

import com.travel.common.file.FileStoragePort;
import com.travel.common.file.FileStorageProperties;
import com.travel.common.file.ImageValidator;
import com.travel.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;
import java.util.UUID;
import java.io.ByteArrayInputStream;

/**
 * 图片上传端点（F104 P1）：仅内部/管理端使用（景点图），对象存 MinIO attractions 桶。
 * 仅支持 jpg/jpeg/png，≤5MB。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileController {

    private static final long MAX_SIZE = 5L * 1024 * 1024;
    private static final Set<String> BUCKETS = Set.of("attractions", "avatars");

    private final FileStoragePort fileStoragePort;
    private final FileStorageProperties props;

    @PostMapping("/images")
    public R<String> uploadImage(@RequestParam("file") MultipartFile file,
                                 @RequestParam(value = "bucket", defaultValue = "attractions") String bucket) {
        if (!BUCKETS.contains(bucket)) {
            return R.fail(40001, "bucket 仅支持 attractions/avatars");
        }
        if (file.isEmpty()) {
            return R.fail(40001, "文件不能为空");
        }
        if (file.getSize() > MAX_SIZE) {
            return R.fail(40001, "图片不能超过 5MB");
        }
        try {
            byte[] data = file.getBytes();
            ImageValidator.validate(file.getOriginalFilename(), file.getContentType());
            ImageValidator.validate(data); // M3-1：魔数校验
            String original = file.getOriginalFilename() == null ? "image.png"
                    : file.getOriginalFilename();
            String ext = original.contains(".")
                    ? original.substring(original.lastIndexOf('.')).toLowerCase()
                    : ".png";
            String object = UUID.randomUUID().toString().replace("-", "") + ext;
            String url = fileStoragePort.upload(new ByteArrayInputStream(data), data.length,
                    file.getContentType(), bucket, object);
            log.info("[File] 上传成功: bucket={}, object={}", bucket, object);
            return R.ok(url);
        } catch (IllegalArgumentException e) {
            return R.fail(40001, e.getMessage());
        } catch (Exception e) {
            log.error("[File] 上传失败", e);
            return R.fail(50003, "上传失败: " + e.getMessage());
        }
    }
}
