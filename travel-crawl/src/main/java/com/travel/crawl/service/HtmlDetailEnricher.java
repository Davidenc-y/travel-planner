package com.travel.crawl.service;

import com.travel.crawl.config.CrawlProperties;
import com.travel.crawl.model.CrawlItem;
import com.travel.crawl.util.RequestThrottle;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * jsoup 详情补充源（F104 P2，默认关闭）：按 siteUrl 模板抓取详情页 meta description 补充
 * 描述；失败/关闭时原样返回（不阻断主链路）。
 */
@Slf4j
@Component
public class HtmlDetailEnricher {

    private final CrawlProperties props;
    private final RequestThrottle throttle;

    public HtmlDetailEnricher(CrawlProperties props, RequestThrottle throttle) {
        this.props = props;
        this.throttle = throttle;
    }

    public List<CrawlItem> enrich(List<CrawlItem> items) {
        if (!props.getEnrich().isEnabled() || props.getEnrich().getSiteUrl().isBlank()) {
            return items;
        }
        List<CrawlItem> out = new ArrayList<>(items.size());
        for (CrawlItem it : items) {
            if (!(it.description() == null || it.description().isBlank())) {
                out.add(it);
                continue;
            }
            try {
                throttle.waitIfNeeded();
                String url = props.getEnrich().getSiteUrl()
                        .replace("{name}", URLEncoder.encode(it.name(), StandardCharsets.UTF_8))
                        .replace("{city}", URLEncoder.encode(it.city() == null ? "" : it.city(), StandardCharsets.UTF_8));
                Document doc = Jsoup.connect(url)
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
                        .timeout(15000).get();
                String desc = doc.select("meta[name=description]").attr("content");
                if (desc.isBlank()) {
                    desc = doc.select("p").first() == null ? "" : doc.select("p").first().text();
                }
                if (desc.length() > 500) {
                    desc = desc.substring(0, 500);
                }
                out.add(desc.isBlank() ? it
                        : new CrawlItem(it.name(), it.city(), it.district(), it.type(), desc,
                        it.lat(), it.lng(), it.address(), it.openHours(), it.ticketPrice(),
                        it.freeEntry(), it.rating(), it.ratingCount(), it.tags(),
                        it.recommendedDuration(), it.imageUrl(), it.source()));
            } catch (Exception e) {
                log.warn("[Enrich] 详情补充失败（原样保留）: name={}, error={}", it.name(), e.getMessage());
                out.add(it);
            }
        }
        return out;
    }
}
