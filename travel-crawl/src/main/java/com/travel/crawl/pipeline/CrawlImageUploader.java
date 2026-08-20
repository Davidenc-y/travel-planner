package com.travel.crawl.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.crawl.config.CrawlProperties;
import com.travel.crawl.model.AttractionRaw;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * 爬取图片入 MinIO（F104 P1 导入联动）：抓取后、写文件前，把高德图床 URL
 * 下载并经 knowledge 上传端点（/api/v1/files/images?bucket=attractions）转存 MinIO，
 * 使 t_attraction.imageUrl 指向业务对象存储。
 *
 * <p>F119：并行下载+转存（虚拟线程 + 有界信号量），按原顺序收集；降级语义不变——
 * 关闭开关 / 下载失败 / 非 jpg|jpeg|png / 超限时，原样保留原始 URL，不阻断主抓取链路。</p>
 */
@Slf4j
@Component
public class CrawlImageUploader {

    private final CrawlProperties props;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    /** 可注入执行器（测试）；null=每批新建虚拟线程执行器 */
    private final ExecutorService executor;
    /** 可注入信号量（测试）；null=每批按 parallelism 新建 */
    private final Semaphore semaphore;

    @Autowired
    public CrawlImageUploader(CrawlProperties props) {
        this(props, null, null);
    }

    /** 测试/定制构造：注入执行器与信号量（null 时按配置自动创建） */
    CrawlImageUploader(CrawlProperties props, ExecutorService executor, Semaphore semaphore) {
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15)).build();
        this.executor = executor;
        this.semaphore = semaphore;
    }

    /**
     * 并行上传（F119）：虚拟线程 + 有界信号量；按原顺序收集；
     * 单条失败保留原 URL（降级语义与串行一致）。
     */
    public List<AttractionRaw> upload(List<AttractionRaw> items) {
        if (!props.getImage().isUploadEnabled()) {
            return items;
        }
        if (items == null || items.isEmpty()) {
            return items;
        }
        int parallelism = Math.max(1, Math.min(props.getImage().getParallelism(), 8));
        Semaphore gate = semaphore != null ? semaphore : new Semaphore(parallelism);
        ExecutorService exec = executor != null ? executor
                : Executors.newVirtualThreadPerTaskExecutor();
        List<String> originals = new ArrayList<>(items.size());
        List<Future<AttractionRaw>> futures = new ArrayList<>(items.size());
        for (AttractionRaw it : items) {
            originals.add(it.imageUrl());
            futures.add(exec.submit(() -> uploadItem(it, gate)));
        }
        List<AttractionRaw> out = new ArrayList<>(items.size());
        int withImage = 0;
        int uploaded = 0;
        long t0 = System.currentTimeMillis();
        try {
            for (int i = 0; i < futures.size(); i++) {
                AttractionRaw r;
                try {
                    r = futures.get(i).get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    r = items.get(i);
                } catch (ExecutionException e) {
                    log.warn("[ImageUpload] 任务异常（原样保留）: name={}, error={}",
                            items.get(i).name(),
                            e.getCause() == null ? e : e.getCause().getMessage());
                    r = items.get(i);
                }
                String orig = originals.get(i);
                if (orig != null && !orig.isBlank()) {
                    withImage++;
                    if (r.imageUrl() != null && !r.imageUrl().equals(orig)) {
                        uploaded++;
                    }
                }
                out.add(r);
            }
        } finally {
            if (executor == null) {
                exec.shutdown();
            }
        }
        log.info("[ImageUpload] 阶段耗时={}ms, 并行={}, 有图={}, 成功={}, 失败={}",
                System.currentTimeMillis() - t0, parallelism, withImage, uploaded,
                withImage - uploaded);
        return out;
    }

    /** 单条下载+转存：Semaphore 限并发；异常/失败返回原条目 */
    private AttractionRaw uploadItem(AttractionRaw it, Semaphore gate) {
        String imageUrl = it.imageUrl();
        if (imageUrl == null || imageUrl.isBlank()) {
            return it;
        }
        try {
            gate.acquire();
            try {
                String minioUrl = uploadOne(imageUrl);
                if (minioUrl != null && !minioUrl.isBlank()) {
                    return new AttractionRaw(it.poiId(), it.name(), it.city(), it.district(),
                            it.type(), it.description(), it.lat(), it.lng(), it.address(),
                            it.openHours(), it.ticketPrice(), it.freeEntry(), it.rating(),
                            it.ratingCount(), it.tags(), it.recommendedDuration(), minioUrl,
                            it.source(), it.confidence(), it.imageUrls(), it.fetchedAt());
                }
                return it;
            } finally {
                gate.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[ImageUpload] 中断（原样保留）: name={}", it.name());
            return it;
        } catch (Exception e) {
            log.warn("[ImageUpload] 转存 MinIO 失败（保留原 URL）: name={}, error={}",
                    it.name(), e.getMessage());
            return it;
        }
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
