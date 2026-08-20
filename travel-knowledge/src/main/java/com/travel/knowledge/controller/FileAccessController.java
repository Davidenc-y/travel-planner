package com.travel.knowledge.controller;

import com.travel.common.file.FileStoragePort;
import com.travel.common.file.FileStorageProperties;
import com.travel.common.result.R;
import com.travel.knowledge.file.FileAccessSupport;
import io.minio.errors.ErrorResponseException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * F121：对象访问网关。
 *
 * <ul>
 *   <li>/proxy：应用代理流式读取（<img> 直接引用；HTTP 语义 200/400/404/500）</li>
 *   <li>/presign：返回 7 天签名 URL（presign 模式）</li>
 *   <li>/resolve：把存储 URL 解析为当前访问模式下可加载的 src（兼容存量/外部 URL）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileAccessController {

    private static final String CACHE_CONTROL = "public, max-age=86400, immutable";

    private final FileStoragePort fileStoragePort;
    private final FileStorageProperties props;
    private final FileAccessSupport support;

    /** 图片代理：校验 → 流式读取 → 缓存头（对象名 UUID 不可变） */
    @GetMapping("/proxy")
    public void proxy(@RequestParam String bucket, @RequestParam String object,
                      HttpServletResponse response) throws Exception {
        if (!support.allowedBucket(bucket)) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "bucket 仅支持 attractions/avatars");
            return;
        }
        if (!support.isValidObject(object)) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "object 非法");
            return;
        }
        try (InputStream in = fileStoragePort.read(bucket, object)) {
            response.setContentType(support.contentType(object));
            response.setHeader("Cache-Control", CACHE_CONTROL);
            in.transferTo(response.getOutputStream());
            response.flushBuffer();
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof ErrorResponseException err
                    && "NoSuchKey".equals(err.errorResponse().code())) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "对象不存在");
            } else {
                log.warn("[FileAccess] 代理读取失败: {}/{} -> {}", bucket, object, e.getMessage());
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "读取失败");
            }
        }
    }

    /** 预签名 URL（presign 模式，7 天） */
    @GetMapping("/presign")
    public R<Map<String, String>> presign(@RequestParam String bucket, @RequestParam String object) {
        if (!support.allowedBucket(bucket)) {
            return R.fail(40001, "bucket 仅支持 attractions/avatars");
        }
        if (!support.isValidObject(object)) {
            return R.fail(40002, "object 非法");
        }
        try {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("url", fileStoragePort.presignedGetUrl(bucket, object));
            m.put("mode", "presign");
            return R.ok(m);
        } catch (Exception e) {
            log.warn("[FileAccess] 预签名失败: {}/{} -> {}", bucket, object, e.getMessage());
            return R.fail(50003, "预签名失败");
        }
    }

    /** 兼容存量存储 URL / 外部 URL：按 access-mode 返回可加载 src */
    @GetMapping("/resolve")
    public R<Map<String, String>> resolve(@RequestParam String url, HttpServletRequest request) {
        if (url == null || url.isBlank()) {
            return R.fail(40001, "url 不能为空");
        }
        FileAccessSupport.Resolved r = support.parse(url);
        if (r == null) {
            return R.ok(Map.of("src", url, "mode", "external"));
        }
        String mode = props.getAccessMode() == null ? "proxy" : props.getAccessMode();
        String src;
        try {
            switch (mode) {
                case "presign" -> src = fileStoragePort.presignedGetUrl(r.bucket(), r.object());
                case "direct" -> {
                    String base = (props.getPublicEndpoint() == null || props.getPublicEndpoint().isBlank())
                            ? props.getEndpoint() : props.getPublicEndpoint();
                    src = base + "/" + r.bucket() + "/" + r.object();
                }
                default -> src = ServletUriComponentsBuilder.fromCurrentContextPath().toUriString()
                        + "/api/v1/files/proxy?bucket=" + r.bucket() + "&object=" + r.object();
            }
        } catch (Exception e) {
            log.warn("[FileAccess] resolve 失败: {}", e.getMessage());
            return R.fail(50003, "对象解析失败");
        }
        Map<String, String> m = new LinkedHashMap<>();
        m.put("src", src);
        m.put("mode", mode);
        return R.ok(m);
    }
}
