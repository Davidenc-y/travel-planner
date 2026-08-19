package com.travel.crawl.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 爬虫配置（F104 骨架）：对应 yml {@code travel.crawl.*}。
 * 仅配置占位；抓取逻辑后续实现时直接注入本类。
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.crawl")
public class CrawlProperties {

    /** 总开关 */
    private boolean enabled = true;

    /** 单源请求最小间隔（毫秒），默认 11 分钟（免费配额 5000/月 测算，F104 2.2/2.5 节） */
    private long minIntervalMs = 660_000L;

    /** 随机退避区间（毫秒），默认 30s~60s */
    private long jitterMinMs = 30_000L;
    private long jitterMaxMs = 60_000L;

    /** 定时任务 cron，默认每小时一轮 */
    private String scheduleCron = "0 0 * * * ?";

    /** 每轮请求上限（月度配额控制，默认 5） */
    private int maxRequestsPerRound = 5;

    /** 高德免费月度配额（搜索服务 5000 次/月） */
    private int monthlyQuota = 5000;

    /** 配额预警比例（累计到 85% 告警） */
    private double monthlyWarnRatio = 0.85;

    /** 配额重置日（每月 1 日 0 点重置） */
    private int monthlyResetDay = 1;

    /** 分批轮转：每轮抓取城市数（默认 2） */
    private int batchCitiesPerRound = 2;

    /** 全量刷新周期（小时），默认 168（每周全量一次） */
    private int fullRefreshIntervalHours = 168;

    /** 每城每轮最多请求页数（默认 3） */
    private int pageLimitPerCity = 3;

    /** 抓取产物目录（0/1 前缀文件所在目录） */
    private String fileDir = "D:/IntelliJ_IDEA/project_graduate/travel-planner/scripts/data/crawl";

    /** 串行队列容量 */
    private int queueCapacity = 10_000;

    /** knowledge 导入地址（读取后调用导入+向量化） */
    private String knowledgeBaseUrl = "http://localhost:8082";

    /** 内部测试接口开关（默认关闭，不对外开放） */
    private boolean testEndpointEnabled = false;

    /** 导入去重与更新配置（F104 2.9） */
    private Import importCfg = new Import();

    /** jsoup 详情补充源（默认关闭） */
    private Enrich enrich = new Enrich();

    /** 爬取图片入 MinIO（F104 P1 导入联动，默认开启，失败降级保留原 URL） */
    private Image image = new Image();

    /** 流水线 MQ 开关（Phase 2 预留，默认 false 使用本地直连） */
    private boolean pipelineMqEnabled = false;

    /** 官方 API 配置（高德） */
    private Amap amap = new Amap();

    /** User-Agent 轮换池 */
    private List<String> userAgents = new ArrayList<>();

    @Data
    public static class Amap {
        /** 高德 Web 服务 Key（官网控制台申请后填入） */
        private String webApiKey = "";

        /** 高德地点搜索 API 地址 */
        private String baseUrl = "https://restapi.amap.com/v5/place/text";

        /** 抓取城市清单 */
        private List<String> cities = List.of("北京", "上海", "广州", "深圳", "杭州", "成都", "西安", "厦门");

        /** POI 类型（景点） */
        private String types = "110000";
    }

    @Data
    public static class Import {
        /** 爬虫刷新是否启用 upsert（true=已存在按 name+city 更新） */
        private boolean updateExisting = true;

        /** 别名表（归一化去重）：规范名 → 别名列表，如 故宫博物院 → 故宫 */
        private Map<String, String> aliasMap = new HashMap<>();
    }

    @Data
    public static class Enrich {
        /** jsoup 详情补充开关（默认关闭） */
        private boolean enabled = false;

        /** 详情检索地址模板（{name}/{city} 占位），如 mafengwo 搜索页 */
        private String siteUrl = "";
    }

    @Data
    public static class Image {
        /** 抓取图片上传 MinIO 开关（经 knowledge /api/v1/files/images） */
        private boolean uploadEnabled = true;

        /** 单张图片最大字节数（默认 5MB） */
        private long maxBytes = 5L * 1024 * 1024;

        /** 下载/上传超时（毫秒，默认 15s） */
        private int timeoutMs = 15_000;
    }
}
