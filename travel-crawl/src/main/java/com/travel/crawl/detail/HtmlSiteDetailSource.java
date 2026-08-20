package com.travel.crawl.detail;

import com.travel.core.data.SourceConfidence;
import com.travel.crawl.config.CrawlProperties;
import com.travel.crawl.model.AttractionRaw;
import com.travel.crawl.util.RequestThrottle;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * HTML 站点详情补充源（F115 T1，由 F104 HtmlDetailEnricher 迁移为 DetailSource SPI）：
 * 按 siteUrl 模板抓取详情页 meta description 补充描述；默认关闭
 * （仅未来合规站点接入时开启；当前 Robots/反爬实证结论见 F115/F117）。
 */
@Slf4j
@Component
@Order(2)
public class HtmlSiteDetailSource implements DetailSource {

    private final CrawlProperties props;
    private final RequestThrottle throttle;

    public HtmlSiteDetailSource(CrawlProperties props) {
        this.props = props;
        this.throttle = new RequestThrottle(
                props.getDetail().getHtml().getMinIntervalMs(), 0, 0);
    }

    @Override
    public String name() {
        return "html-site";
    }

    @Override
    public boolean enabled() {
        CrawlProperties.Html html = props.getDetail().getHtml();
        return html.isEnabled() && html.getSiteUrl() != null && !html.getSiteUrl().isBlank();
    }

    @Override
    public DetailEnrichment enrich(AttractionRaw item) {
        if (item == null || item.name() == null || item.name().isBlank()) {
            return DetailEnrichment.empty();
        }
        try {
            throttle.waitIfNeeded();
            String url = props.getDetail().getHtml().getSiteUrl()
                    .replace("{name}", URLEncoder.encode(item.name(), StandardCharsets.UTF_8))
                    .replace("{city}", URLEncoder.encode(item.city() == null ? "" : item.city(),
                            StandardCharsets.UTF_8));
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
            return desc.isBlank() ? DetailEnrichment.empty()
                    : new DetailEnrichment(desc, null, null, null,
                    List.of(url), List.of(), SourceConfidence.ENRICH);
        } catch (Exception e) {
            log.warn("[HtmlSite] 详情补充失败（原样降级）: name={}, error={}", item.name(), e.getMessage());
            return DetailEnrichment.empty();
        }
    }
}
