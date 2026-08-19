package com.travel.crawl.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.crawl.config.CrawlProperties;
import com.travel.crawl.model.CrawlItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 爬取图片入 MinIO（F104 P1 导入联动）：抓取后、写文件前，把高德图床 URL
 * 下载并经 knowledge 上传端点（/api/v1/files/images?bucket=attractions）转存 MinIO，
 * 使 t_attraction.imageUrl 指向业务对象存储。
 *
 * <p>降级语义：关闭开关 / 下载失败 / 非 jpg|jpeg|png / 超限时，原样保留原始 URL，
 * 不阻断主抓取链路（符合 F104 2.2 失败降级）。</p>
 */
@Slf4j
@Component
public class CrawlImageUploader {

    private final CrawlProperties props;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public CrawlImageUploader(CrawlProperties props) {
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15)).build();
    }

    /** 逐条上传；失败保留原 URL */
    public List<CrawlItem> upload(List<CrawlItem> items) {
        if (!props.getImage().isUploadEnabled()) {
            return items;
        }
        if (items == null || items.isEmpty()) {
            return items;
        }
        List<CrawlItem> out = new ArrayList<>(items.size());
        for (CrawlItem it : items) {
            String imageUrl = it.imageUrl();
            if (imageUrl == null || imageUrl.isBlank()) {
                out.add(it);
                continue;
            }
            try {
                String minioUrl = uploadOne(imageUrl);
                if (minioUrl != null && !minioUrl.isBlank()) {
                    out.add(new CrawlItem(it.name(), it.city(), it.district(), it.type(),
                            it.description(), it.lat(), it.lng(), it.address(), it.openHours(),
                            it.ticketPrice(), it.freeEntry(), it.rating(), it.ratingCount(),
                            it.tags(), it.recommendedDuration(), minioUrl, it.source()));
                    continue;
                }
            } catch (Exception e) {
                log.warn("[ImageUpload] 转存 MinIO 失败（保留原 URL）: name={}, error={}",
                        it.name(), e.getMessage());
            }
            out.add(it);
        }
        return out;
    }

    /** 下载并上传单张图片（包可见，便于单元测试边界方法） */
    String uploadOne(String imageUrl) throws Exception {
        String ext = guessExt(imageUrl);
        if (ext == null) {
            return null;
        }
        String contentType = "image/jpeg".equals(ext) ? "image/jpeg" : "image/png";
        HttpRequest dl = HttpRequest.newBuilder(URI.create(imageUrl))
                .timeout(Duration.ofMillis(props.getImage().getTimeoutMs()))
                .GET().build();
        HttpResponse<byte[]> dlResp = httpClient.send(dl, HttpResponse.BodyHandlers.ofByteArray());
        if (dlResp.statusCode() != 200 || dlResp.body() == null || dlResp.body().length == 0) {
            log.warn("[ImageUpload] 图片下载失败: http={}", dlResp.statusCode());
            return null;
        }
        if (dlResp.body().length > props.getImage().getMaxBytes()) {
            log.warn("[ImageUpload] 图片超过 {} bytes，跳过", props.getImage().getMaxBytes());
            return null;
        }
        String boundary = "----travel" + Long.toHexString(System.nanoTime());
        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        byte[] body = buildMultipart(fileName, contentType, dlResp.body(), boundary);
        String uploadUrl = props.getKnowledgeBaseUrl() + "/api/v1/files/images?bucket=attractions";
        HttpRequest up = HttpRequest.newBuilder(URI.create(uploadUrl))
                .timeout(Duration.ofMillis(props.getImage().getTimeoutMs()))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        HttpResponse<String> upResp = httpClient.send(up, HttpResponse.BodyHandlers.ofString());
        JsonNode json = mapper.readTree(upResp.body());
        if (upResp.statusCode() == 200 && json.path("code").asInt() == 200) {
            return json.path("data").asText(null);
        }
        log.warn("[ImageUpload] 上传端点返回异常: http={}, code={}", upResp.statusCode(),
                json.path("code").asInt(-1));
        return null;
    }

    /** 组装 multipart/form-data（包可见，便于单元测试） */
    static byte[] buildMultipart(String fileName, String contentType, byte[] data, String boundary)
            throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length + 512);
        write(bos, "--" + boundary + "\r\n");
        write(bos, "Content-Disposition: form-data; name=\"file\"; filename=\""
                + fileName + "\"\r\n");
        write(bos, "Content-Type: " + contentType + "\r\n\r\n");
        bos.write(data);
        write(bos, "\r\n--" + boundary + "--\r\n");
        return bos.toByteArray();
    }

    private static void write(ByteArrayOutputStream bos, String s) throws IOException {
        bos.write(s.getBytes(StandardCharsets.UTF_8));
    }

    /** 从 URL 推断扩展名（去 query 段）；仅 jpg/jpeg/png 返回，否则 null */
    private static String guessExt(String url) {
        String path = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return null;
        }
        String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        if ("jpg".equals(ext) || "jpeg".equals(ext)) {
            return "jpeg";
        }
        if ("png".equals(ext)) {
            return "png";
        }
        return null;
    }
}
