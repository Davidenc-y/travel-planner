package com.travel.crawl.pipeline;

import com.travel.core.data.MergeRules;
import com.travel.core.data.SourceConfidence;
import com.travel.crawl.config.CrawlProperties;
import com.travel.crawl.detail.DetailEnrichment;
import com.travel.crawl.detail.DetailSource;
import com.travel.crawl.model.AttractionRaw;
import com.travel.crawl.util.Normalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 采集管道（F110-B）：enrich（DetailSource 多源补充，F115）→ image（MinIO 转存）
 * → validate（名称/城市非空）→ dedupe（poiId 优先，其次 name+city 归一化键）。
 * 阶段可插拔、失败降级不阻断。
 */
@Slf4j
@Service
public class CrawlPipeline {

    private final List<DetailSource> detailSources;
    private final CrawlImageUploader imageUploader;
    private final Normalizer normalizer;
    private final CrawlProperties props;

    public CrawlPipeline(CrawlProperties props, List<DetailSource> detailSources,
                         CrawlImageUploader imageUploader, Normalizer normalizer) {
        this.props = props;
        this.detailSources = detailSources;
        this.imageUploader = imageUploader;
        this.normalizer = normalizer;
    }

    public List<AttractionRaw> process(List<AttractionRaw> items) {
        if (items == null || items.isEmpty()) {
            return items;
        }
        List<AttractionRaw> enriched = enrich(items);
        List<AttractionRaw> withImages = imageUploader.upload(enriched);
        Map<String, AttractionRaw> byKey = new LinkedHashMap<>();
        for (AttractionRaw it : withImages) {
            if (it.name() == null || it.name().isBlank()
                    || it.city() == null || it.city().isBlank()) {
                log.warn("[Pipeline] 丢弃无效条目: name={}, city={}", it.name(), it.city());
                continue;
            }
            String key = it.poiId() != null && !it.poiId().isBlank()
                    ? "poi:" + it.poiId()
                    : normalizer.dedupKey(it);
            byKey.putIfAbsent(key, it);
        }
        return new ArrayList<>(byKey.values());
    }

    /** F115 T2：按启用顺序调用 DetailSource，逐字段补缺（MergeRules：MANUAL>ENRICH>API） */
    private List<AttractionRaw> enrich(List<AttractionRaw> items) {
        if (detailSources == null || detailSources.isEmpty()) {
            return items;
        }
        int budget = Math.max(0, props.getDetail().getMaxItemsPerBatch());
        int used = 0;
        boolean budgetLogged = false;
        List<AttractionRaw> out = new ArrayList<>(items.size());
        for (AttractionRaw it : items) {
            AttractionRaw current = it;
            for (DetailSource source : detailSources) {
                if (!source.enabled()) {
                    continue;
                }
                boolean needsNetwork = source.mayRequireNetwork(current);
                if (budget > 0 && used >= budget && needsNetwork) {
                    if (!budgetLogged) {
                        log.info("[Pipeline] 达每批补全预算 {}，剩余条目跳过补充（下轮补）", budget);
                        budgetLogged = true;
                    }
                    break;
                }
                DetailEnrichment de;
                try {
                    de = source.enrich(current);
                } catch (Exception e) {
                    log.warn("[Pipeline] 补充源 {} 异常: {}", source.name(), e.getMessage());
                    continue;
                }
                if (needsNetwork && de != null && de.hasAny()) {
                    used++;
                }
                AttractionRaw merged = merge(current, de);
                if (merged != current) {
                    log.debug("[Pipeline] 补充源 {} 生效: name={}", source.name(), current.name());
                }
                current = merged;
            }
            out.add(current);
        }
        return out;
    }

    /**
     * 仅补 description/lat/lng；身份与其余字段原样保留。
     * 新记录置信度置 ENRICH（内存态），source 字符串保持不变（落库后仍可按 source 清理回归）。
     */
    private AttractionRaw merge(AttractionRaw it, DetailEnrichment de) {
        if (de == null || !de.hasAny()) {
            return it;
        }
        String desc = MergeRules.choose(it.description(), it.confidence(),
                de.description(), de.confidence());
        Double lat = MergeRules.choose(it.lat(), it.confidence(), de.lat(), de.confidence());
        Double lng = MergeRules.choose(it.lng(), it.confidence(), de.lng(), de.confidence());
        if (eq(desc, it.description()) && eq(lat, it.lat()) && eq(lng, it.lng())) {
            return it;
        }
        return new AttractionRaw(it.poiId(), it.name(), it.city(), it.district(), it.type(),
                desc, lat, lng, it.address(), it.openHours(), it.ticketPrice(), it.freeEntry(),
                it.rating(), it.ratingCount(), it.tags(), it.recommendedDuration(), it.imageUrl(),
                it.source(), SourceConfidence.ENRICH, it.imageUrls(), it.fetchedAt());
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }
}
