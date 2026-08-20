package com.travel.common.file;

import java.util.Locale;
import java.util.Set;

/** 图片校验（F104 P1）：仅 .jpg/.jpeg/.png（名称 + Content-Type 双校验） */
public final class ImageValidator {

    private static final Set<String> ALLOWED_EXT = Set.of("jpg", "jpeg", "png");
    private static final Set<String> ALLOWED_MIME = Set.of(
            "image/jpeg", "image/png");

    private ImageValidator() {
    }

    public static void validate(String fileName, String contentType) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        String name = fileName.toLowerCase(Locale.ROOT);
        if (name.contains("..") || name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("文件名非法（禁止路径穿越）");
        }
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1) : "";
        if (!ALLOWED_EXT.contains(ext)) {
            throw new IllegalArgumentException("仅支持 jpg/jpeg/png 图片");
        }
        if (contentType != null && !ALLOWED_MIME.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("仅支持 image/jpeg 或 image/png");
        }
    }

    /** M3-1：图片魔数校验（jpg: FF D8 FF；png: 89 50 4E 47 0D 0A 1A 0A） */
    public static void validate(byte[] content) {
        if (content == null || content.length < 4) {
            throw new IllegalArgumentException("图片内容为空或过短");
        }
        boolean jpeg = (content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xD8
                && (content[2] & 0xFF) == 0xFF;
        boolean png = (content[0] & 0xFF) == 0x89 && (content[1] & 0xFF) == 0x50
                && (content[2] & 0xFF) == 0x4E && (content[3] & 0xFF) == 0x47;
        if (!jpeg && !png) {
            throw new IllegalArgumentException("图片内容校验失败（非 jpg/png）");
        }
    }
}
