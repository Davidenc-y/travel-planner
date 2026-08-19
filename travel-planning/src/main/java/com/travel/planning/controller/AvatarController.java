package com.travel.planning.controller;

import com.travel.common.entity.User;
import com.travel.common.file.FileStoragePort;
import com.travel.common.file.FileStorageProperties;
import com.travel.common.file.ImageValidator;
import com.travel.common.result.R;
import com.travel.planning.repository.UserMapper;
import com.travel.planning.util.AuthUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

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
    private final UserMapper userMapper;

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
            ImageValidator.validate(file.getOriginalFilename(), file.getContentType());
        } catch (IllegalArgumentException e) {
            return R.fail(40001, e.getMessage());
        }
        try {
            String original = file.getOriginalFilename() == null ? "avatar.png"
                    : file.getOriginalFilename();
            String ext = original.contains(".")
                    ? original.substring(original.lastIndexOf('.')).toLowerCase()
                    : ".png";
            String object = "u" + userId + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16) + ext;
            String url = fileStoragePort.upload(file.getInputStream(), file.getSize(),
                    file.getContentType(), props.getAvatarsBucket(), object);

            User user = new User();
            user.setId(userId);
            user.setAvatar(url);
            userMapper.updateById(user);
            log.info("[Avatar] 头像更新成功: userId={}", userId);
            return R.ok(url);
        } catch (Exception e) {
            log.error("[Avatar] 头像上传失败", e);
            return R.fail(50003, "上传失败: " + e.getMessage());
        }
    }
}
