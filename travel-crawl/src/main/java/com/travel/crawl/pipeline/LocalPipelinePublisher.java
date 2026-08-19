package com.travel.crawl.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.crawl.config.CrawlProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

/** 本地直连：调用 knowledge 导入接口（upsert）完成导入+向量化。 */
@Slf4j
@Component
@ConditionalOnProperty(name = "travel.crawl.pipeline-mq-enabled", havingValue = "false", matchIfMissing = true)
public class LocalPipelinePublisher implements PipelinePublisher {

    private final CrawlProperties props;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public LocalPipelinePublisher(CrawlProperties props) {
        this.props = props;
    }

    @Override
    public PipelineResult publish(Path file) {
        try {
            String filePath = URLEncoder.encode(file.toAbsolutePath().toString(), StandardCharsets.UTF_8);
            String url = props.getKnowledgeBaseUrl() + "/api/v1/etl/import?filePath=" + filePath
                    + "&mode=" + (props.getImportCfg().isUpdateExisting() ? "upsert" : "insert");
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(300))
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode json = mapper.readTree(resp.body());
            boolean ok = resp.statusCode() == 200 && json.path("code").asInt() == 200;
            int inserted = json.path("data").asInt(0);
            int updated = 0;
            int skipped = 0;
            String statsHeader = resp.headers().firstValue("X-Import-Stats").orElse(null);
            if (statsHeader != null && !statsHeader.isBlank()) {
                JsonNode stats = mapper.readTree(statsHeader);
                inserted = stats.path("inserted").asInt(inserted);
                updated = stats.path("updated").asInt(0);
                skipped = stats.path("skipped").asInt(0);
            }
            log.info("[Pipeline] 导入结果: file={}, http={}, code={}, inserted={}, updated={}, skipped={}",
                    file.getFileName(), resp.statusCode(), json.path("code").asInt(),
                    inserted, updated, skipped);
            return new PipelineResult(ok, inserted, updated, skipped);
        } catch (Exception e) {
            log.warn("[Pipeline] 导入失败（文件保留 0 前缀待重试）: file={}, error={}",
                    file.getFileName(), e.getMessage());
            return new PipelineResult(false, 0, 0, 0);
        }
    }
}
