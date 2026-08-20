package com.travel.planning.controller;

import com.travel.common.entity.User;
import com.travel.common.file.FileStoragePort;
import com.travel.common.file.FileStorageProperties;
import com.travel.common.file.ImageValidator;
import com.travel.common.result.R;
import com.travel.planning.service.UserService;
import com.travel.planning.util.AuthUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;
import java.io.ByteArrayInputStream;

/**
 * 用户头像上传（F104 P1）：图片存 MinIO avatars 桶并回写 t_user.avatar。
 * 仅支持 jpg/jpeg/png，≤5MB。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class AvatarController {

    private static final long MAX_SIZE = 5L * 1024 * 1024;

    private final FileStoragePort fileStoragePort;
    private final FileStorageProperties props;
    private final UserService userService;

    @PostMapping("/avatar")
    public R<String> uploadAvatar(@RequestParam("file") MultipartFile file) {
        Long userId = AuthUtils.resolveUserId(null);
        if (file == null || file.isEmpty()) {
            return R.fail(40001, "文件不能为空");
        }
        if (file.getSize() > MAX_SIZE) {
            return R.fail(40001, "图片不能超过 5MB");
        }
        try {
            byte[] data = file.getBytes();
            ImageValidator.validate(file.getOriginalFilename(), file.getContentType());
            ImageValidator.validate(data); // M3-1：魔数校验
            String original = file.getOriginalFilename() == null ? "avatar.png"
                    : file.getOriginalFilename();
            String ext = original.contains(".")
                    ? original.substring(original.lastIndexOf('.')).toLowerCase()
                    : ".png";
            String object = "u" + userId + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16) + ext;
            String url = fileStoragePort.upload(new ByteArrayInputStream(data), data.length,
                    file.getContentType(), props.getAvatarsBucket(), object);

            // F121/P1：更新前清理旧头像对象（best-effort，仅删除 avatars 桶内对象）
            User old = userService.getById(userId);
            if (old != null && old.getAvatar() != null && !old.getAvatar().isBlank()) {
                String oldUrl = old.getAvatar().trim();
                if (oldUrl.contains("/" + props.getAvatarsBucket() + "/")) {
                    String oldObject = oldUrl.substring(oldUrl.lastIndexOf('/') + 1);
                    fileStoragePort.delete(props.getAvatarsBucket(), oldObject);
                    log.info("[Avatar] 已清理旧头像: userId={}, object={}", userId, oldObject);
                }
            }

            userService.updateAvatar(userId, url);
            log.info("[Avatar] 头像更新成功: userId={}", userId);
            return R.ok(url);
        } catch (IllegalArgumentException e) {
            return R.fail(40001, e.getMessage());
        } catch (Exception e) {
            log.error("[Avatar] 头像上传失败", e);
            return R.fail(50003, "上传失败: " + e.getMessage());
        }
    }
}
