package com.travel.crawl.service;

import com.travel.crawl.config.CrawlProperties;
import com.travel.crawl.model.AttractionRaw;
import com.travel.crawl.pipeline.CrawlPipeline;
import com.travel.crawl.pipeline.PipelinePublisher;
import com.travel.crawl.source.CrawlQuery;
import com.travel.crawl.source.CrawlSource;
import com.travel.crawl.store.CrawlFileStore;
import com.travel.crawl.store.CrawlQueue;
import com.travel.crawl.util.QuotaGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 爬虫编排（F110-B）：Source SPI 多源抓取（主源空结果自动回退备胎）→ 管道
 * （enrich/image/validate/dedupe）→ 执行队列（local/redis）→ 发布导入联动。
 * 对外契约（trigger/status/test-rotate）保持不变，兼容 F108 回归。
 */
@Slf4j
@Service
public class CrawlService {

    private final CrawlProperties props;
    private final List<CrawlSource> sources;
    private final CrawlQueue queue;
    private final PipelinePublisher pipelinePublisher;
    private final QuotaGuard quotaGuard;
    private final CrawlFileStore fileStore;
    private final CrawlPipeline pipeline;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile int cityCursor = 0;
    private volatile Map<String, Object> lastResult = Map.of();

    public CrawlService(CrawlProperties props, List<CrawlSource> sources, CrawlQueue queue,
                        PipelinePublisher pipelinePublisher, QuotaGuard quotaGuard,
                        CrawlFileStore fileStore, CrawlPipeline pipeline) {
        this.props = props;
        this.sources = sources;
        this.queue = queue;
        this.pipelinePublisher = pipelinePublisher;
        this.quotaGuard = quotaGuard;
        this.fileStore = fileStore;
        this.pipeline = pipeline;
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
        m.put("queuePending", queue.pendingCount());
        m.put("cities", props.getAmap().getCities());
        m.put("files", fileStore.listFiles().stream().map(p -> p.getFileName().toString()).toList());
        m.put("lastResult", lastResult);
        return m;
    }

    /** 人工补录（F110-B Phase 3）：入队→发布导入（MANUAL 置信度，upsert）→ack */
    public Map<String, Object> importManual(AttractionRaw item) {
        if (!props.isEnabled()) {
            return status("disabled");
        }
        if (!running.compareAndSet(false, true)) {
            return status("busy");
        }
        try {
            queue.enqueue(List.of(item));
            List<String> done = new ArrayList<>();
            int[] st = publishPending(done);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("imported", st[0]);
            m.put("updated", st[1]);
            m.put("skipped", st[2]);
            m.put("importedFiles", done);
            return m;
        } finally {
            running.set(false);
        }
    }

    private Map<String, Object> doRound(String cityOverride, int pageLimit) {
        List<String> cities = pickCities(cityOverride, props.getBatchCitiesPerRound());
        int requests = 0;
        int items = 0;
        int descFilled = 0;
        int descTotal = 0;
        int descRate = 0;
        int imported = 0;
        int updated = 0;
        int skipped = 0;
        List<String> done = new ArrayList<>();
        boolean quotaExhausted = false;
        int maxRequests = Math.max(1, props.getMaxRequestsPerRound());
        outer:
        for (String c : cities) {
            String adcode = props.getAmap().cityAdcodeMap().get(c);
            for (int page = 1; page <= pageLimit; page++) {
                if (requests >= maxRequests) {
                    log.info("[Crawl] 本轮请求数已达上限 {}，停止抓取", maxRequests);
                    break outer;
                }
                List<AttractionRaw> pageItems;
                try {
                    pageItems = fetch(CrawlQuery.ofCity(c, adcode, props.getAmap().getTypes()), page);
                } catch (IllegalStateException e) {
                    quotaExhausted = true;
                    log.warn("[Crawl] 配额耗尽，提前停止: {}", e.getMessage());
                    break;
                }
                requests++;
                pageItems = pipeline.process(pageItems);
                items += pageItems.size();
                descTotal += pageItems.size();
                for (AttractionRaw it : pageItems) {
                    if (it.description() != null && !it.description().isBlank()) {
                        descFilled++;
                    }
                }
                queue.enqueue(pageItems);
                if (pageItems.isEmpty()) {
                    break;
                }
                // F122：每页完成后立即发布导入（drain→publish→ack），避免整轮结束后才入库/ETL
                int[] st = publishPending(done);
                imported += st[0];
                updated += st[1];
                skipped += st[2];
            }
            if (quotaExhausted) {
                break;
            }
        }
        if (descTotal > 0) {
            descRate = (int) Math.round(descFilled * 100.0 / descTotal);
            log.info("[Crawl] 本轮描述填充率: {}/{} = {}%", descFilled, descTotal, descRate);
        }

        // F122：轮末兜底发布（处理预置 0_ 种子文件 / 上轮失败重试，幂等）
        int[] st = publishPending(done);
        imported += st[0];
        updated += st[1];
        skipped += st[2];

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cities", cities);
        m.put("requests", requests);
        m.put("items", items);
        m.put("descriptionFilled", descFilled);
        m.put("descriptionTotal", descTotal);
        m.put("descriptionFillRate", descRate);
        m.put("imported", imported);
        m.put("updated", updated);
        m.put("skipped", skipped);
        m.put("quotaUsed", quotaGuard.used());
        m.put("quotaLimit", quotaGuard.limit());
        m.put("queuePending", queue.pendingCount());
        m.put("importedFiles", done);
        return m;
    }

    /** 发布导入联动：drain → 发布（upsert）→ 成功 ack（local=0→1 / redis=XACK）；失败保 0 下轮重试 */
    private int[] publishPending(List<String> done) {
        int imported = 0;
        int updated = 0;
        int skipped = 0;
        try {
            for (CrawlQueue.CrawlBatch batch : queue.drain(100)) {
                PipelinePublisher.PipelineResult result = pipelinePublisher.publish(Path.of(batch.ref()));
                if (result.ok()) {
                    queue.ack(batch.ref());
                    imported += batch.items().size();
                    updated += result.updated();
                    skipped += result.skipped();
                    done.add(Path.of(batch.ref()).getFileName().toString());
                }
            }
        } catch (Exception e) {
            log.warn("[Crawl] 导入联动失败: {}", e.getMessage());
        }
        if (imported > 0 || updated > 0 || skipped > 0) {
            log.info("[Crawl] 页面导入完成: imported={}, updated={}, skipped={}",
                    imported, updated, skipped);
        }
        return new int[]{imported, updated, skipped};
    }

    /** 多源抓取：优先 amap-text（adcode 优先），空结果依次回退其余启用源 */
    private List<AttractionRaw> fetch(CrawlQuery query, int page) {
        for (CrawlSource source : sources) {
            if (!source.enabled()) {
                continue;
            }
            try {
                List<AttractionRaw> items = source.fetch(query, page);
                if (!items.isEmpty()) {
                    return items;
                }
                log.info("[Crawl] 源 {} 返回空，尝试下一源", source.name());
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                log.warn("[Crawl] 源 {} 异常: {}", source.name(), e.getMessage());
            }
        }
        return List.of();
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
