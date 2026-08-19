package com.travel.crawl.service;

import com.travel.crawl.api.AmapClient;
import com.travel.crawl.config.CrawlProperties;
import com.travel.crawl.model.CrawlItem;
import com.travel.crawl.pipeline.CrawlImageUploader;
import com.travel.crawl.pipeline.PipelinePublisher;
import com.travel.crawl.store.CrawlFileStore;
import com.travel.crawl.util.MonthlyQuotaGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** 爬虫编排（F104 P0/P2）：分批轮转全量 + 定时一轮 + 内部测试循环 + 导入联动。 */
@Slf4j
@Service
public class CrawlService {

    private final CrawlProperties props;
    private final AmapClient amapClient;
    private final CrawlFileStore fileStore;
    private final PipelinePublisher pipelinePublisher;
    private final MonthlyQuotaGuard quotaGuard;
    private final HtmlDetailEnricher enricher;
    private final CrawlImageUploader imageUploader;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile int cityCursor = 0;
    private volatile Map<String, Object> lastResult = Map.of();

    public CrawlService(CrawlProperties props, AmapClient amapClient, CrawlFileStore fileStore,
                        PipelinePublisher pipelinePublisher, MonthlyQuotaGuard quotaGuard,
                        HtmlDetailEnricher enricher, CrawlImageUploader imageUploader) {
        this.props = props;
        this.amapClient = amapClient;
        this.fileStore = fileStore;
        this.pipelinePublisher = pipelinePublisher;
        this.quotaGuard = quotaGuard;
        this.enricher = enricher;
        this.imageUploader = imageUploader;
    }

    /** 执行一轮抓取（可指定单城市覆盖轮转） */
    public Map<String, Object> runRound(String cityOverride) {
        if (!props.isEnabled()) {
            return status("disabled");
        }
        if (!running.compareAndSet(false, true)) {
            return status("busy");
        }
        try {
            lastResult = doRound(cityOverride, props.getPageLimitPerCity());
            return lastResult;
        } finally {
            running.set(false);
        }
    }

    /** 内部测试：循环 rounds 轮分批轮转全量（dryRun 只输出计划） */
    public Map<String, Object> testRotate(int rounds, Integer citiesPerRound, String city,
                                          Integer pageLimit, Boolean dryRun) {
        if (dryRun != null && dryRun) {
            List<String> plan = pickCities(city, citiesPerRound == null
                    ? props.getBatchCitiesPerRound() : citiesPerRound);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dryRun", true);
            m.put("planCities", plan);
            m.put("note", "不发起真实请求");
            return m;
        }
        if (!props.isEnabled()) {
            return status("disabled");
        }
        int safeRounds = Math.max(1, Math.min(rounds <= 0 ? 3 : rounds, 10));
        List<Map<String, Object>> roundsResult = new ArrayList<>();
        for (int i = 0; i < safeRounds; i++) {
            if (!running.compareAndSet(false, true)) {
                return status("busy");
            }
            try {
                roundsResult.add(doRound(city, pageLimit == null ? props.getPageLimitPerCity()
                        : Math.max(1, pageLimit)));
            } finally {
                running.set(false);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rounds", safeRounds);
        m.put("roundsResult", roundsResult);
        int sumInserted = 0;
        int sumUpdated = 0;
        int sumSkipped = 0;
        for (Map<String, Object> r : roundsResult) {
            sumInserted += ((Number) r.getOrDefault("imported", 0)).intValue();
            sumUpdated += ((Number) r.getOrDefault("updated", 0)).intValue();
            sumSkipped += ((Number) r.getOrDefault("skipped", 0)).intValue();
        }
        m.put("inserted", sumInserted);
        m.put("updated", sumUpdated);
        m.put("skipped", sumSkipped);
        m.put("quotaUsed", quotaGuard.used());
        m.put("quotaLimit", quotaGuard.limit());
        m.put("files", fileStore.listFiles().stream().map(p -> p.getFileName().toString()).toList());
        return m;
    }

    public Map<String, Object> status(String state) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", props.isEnabled());
        m.put("state", state == null ? (running.get() ? "running" : "idle") : state);
        m.put("quotaUsed", quotaGuard.used());
        m.put("quotaLimit", quotaGuard.limit());
        m.put("cities", props.getAmap().getCities());
        m.put("files", fileStore.listFiles().stream().map(p -> p.getFileName().toString()).toList());
        m.put("lastResult", lastResult);
        return m;
    }

    private Map<String, Object> doRound(String cityOverride, int pageLimit) {
        List<String> cities = pickCities(cityOverride, props.getBatchCitiesPerRound());
        int requests = 0;
        int items = 0;
        boolean quotaExhausted = false;
        int maxRequests = Math.max(1, props.getMaxRequestsPerRound());
        outer:
        for (String c : cities) {
            for (int page = 1; page <= pageLimit; page++) {
                if (requests >= maxRequests) {
                    log.info("[Crawl] 本轮请求数已达上限 {}，停止抓取", maxRequests);
                    break outer;
                }
                List<CrawlItem> pageItems;
                try {
                    pageItems = amapClient.search(c, page);
                } catch (IllegalStateException e) {
                    quotaExhausted = true;
                    log.warn("[Crawl] 配额耗尽，提前停止: {}", e.getMessage());
                    break;
                }
                requests++;
                pageItems = enricher.enrich(pageItems);
                pageItems = imageUploader.upload(pageItems);
                items += pageItems.size();
                try {
                    fileStore.append(pageItems);
                } catch (Exception e) {
                    log.warn("[Crawl] 文件追加失败: {}", e.getMessage());
                }
                if (pageItems.isEmpty()) {
                    break;
                }
            }
            if (quotaExhausted) {
                break;
            }
        }

        // 导入联动：读取 0_* → 发布导入（upsert）→ 成功置 1
        int imported = 0;
        int updated = 0;
        int skipped = 0;
        List<String> done = new ArrayList<>();
        try {
            for (CrawlFileStore.PendingFile pf : fileStore.readPending()) {
                PipelinePublisher.PipelineResult result = pipelinePublisher.publish(pf.file());
                if (result.ok()) {
                    fileStore.markDone(pf.file());
                    imported += pf.items().size();
                    updated += result.updated();
                    skipped += result.skipped();
                    done.add(pf.file().getFileName().toString());
                }
            }
        } catch (Exception e) {
            log.warn("[Crawl] 导入联动失败: {}", e.getMessage());
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cities", cities);
        m.put("requests", requests);
        m.put("items", items);
        m.put("imported", imported);
        m.put("updated", updated);
        m.put("skipped", skipped);
        m.put("quotaUsed", quotaGuard.used());
        m.put("quotaLimit", quotaGuard.limit());
        m.put("importedFiles", done);
        return m;
    }

    private List<String> pickCities(String override, int batch) {
        List<String> all = props.getAmap().getCities();
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        if (override != null && !override.isBlank()) {
            return List.of(override);
        }
        int n = all.size();
        int b = Math.max(1, Math.min(batch, n));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < b; i++) {
            out.add(all.get((cityCursor + i) % n));
        }
        cityCursor = (cityCursor + b) % n;
        return out;
    }
}
