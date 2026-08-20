package com.travel.crawl.source;

import com.travel.core.data.SourceConfidence;
import com.travel.crawl.config.CrawlProperties;
import com.travel.crawl.model.AttractionRaw;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 测试数据源（F110-B）：travel.crawl.sources.mock-enabled=true 时注册，
 * 供离线回归/联调（不消耗配额、不依赖外部 API）。黄金数据见
 * scripts/regression/data/golden_beijing_sample.json。
 */
@Component
@ConditionalOnProperty(name = "travel.crawl.sources.mock-enabled", havingValue = "true")
public class MockSource implements CrawlSource {

    private final CrawlProperties props;

    public MockSource(CrawlProperties props) {
        this.props = props;
    }

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public List<AttractionRaw> fetch(CrawlQuery query, int pageNum) {
        return List.of(new AttractionRaw(
                "mock-1", "故宫博物院", query.city() == null ? "北京" : query.city(),
                "东城区", "CULTURE", "明清两代帝王祭天祈谷的场所，世界文化遗产。",
                39.9163, 116.3972, "景山前街4号", "8:00-17:00",
                60.0, 0, 4.8, 88400, "[\"文化\",\"历史\"]", "3-4小时",
                "", "mock", SourceConfidence.API,
                List.of(), ""));
    }
}
