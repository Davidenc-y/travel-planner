package com.travel.knowledge.file;

import com.travel.common.file.FileStorageProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * F121：对象访问网关校验/解析助手（bucket 白名单、object 路径校验、Content-Type、
 * 存储 URL 解析）。纯逻辑、无 IO，便于单测。
 */
@Component
public class FileAccessSupport {

    /** 对象名仅允许 UUID/短横线/下划线 + 1~8 位小扩展名（防路径穿越与参数污染） */
    private static final Pattern OBJECT_PATTERN =
            Pattern.compile("[A-Za-z0-9_-]+(\\.[A-Za-z0-9]{1,8})?");

    private final FileStorageProperties props;

    public FileAccessSupport(FileStorageProperties props) {
        this.props = props;
    }

    public record Resolved(String bucket, String object) {
    }

    public boolean allowedBucket(String bucket) {
        if (bucket == null || bucket.isBlank()) {
            return false;
        }
        Set<String> whitelist = Set.of(props.getAttractionsBucket(), props.getAvatarsBucket());
        return whitelist.contains(bucket.trim());
    }

    public boolean isValidObject(String object) {
        return object != null && OBJECT_PATTERN.matcher(object.trim()).matches();
    }

    public String contentType(String object) {
        if (object == null) {
            return "application/octet-stream";
        }
        String lower = object.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        return "application/octet-stream";
    }

    /**
     * 解析存储 URL（完整 MinIO URL 或 bucket/object）→ Resolved；非 MinIO/外部 URL 返回 null。
     * 绝对 URL 仅当 host:port 匹配配置 endpoint 时才视为 MinIO 对象（防误重写外部链接）。
     */
    public Resolved parse(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String u = url.trim();
        String path;
        if (u.contains("://")) {
            try {
                URI uri = URI.create(u);
                String hostPort = hostPort(uri);
                String expected = hostPort(URI.create(props.getEndpoint()));
                if (!hostPort.equals(expected)) {
                    return null;
                }
                path = uri.getPath();
            } catch (Exception e) {
                return null;
            }
        } else {
            path = u;
        }
        if (path != null && path.startsWith("/")) {
            path = path.substring(1);
        }
        String[] parts = path == null ? new String[0] : path.split("/", -1);
        if (parts.length != 2) {
            return null;
        }
        String bucket = parts[0];
        String object = parts[1];
        if (!allowedBucket(bucket) || !isValidObject(object)) {
            return null;
        }
        return new Resolved(bucket, object);
    }

    private static String hostPort(URI uri) {
        int port = uri.getPort();
        return uri.getHost() + ":" + (port > 0 ? port : (uri.getScheme().equals("https") ? 443 : 80));
    }
}
