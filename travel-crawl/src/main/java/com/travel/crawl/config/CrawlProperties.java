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

    /** 人工补录接口开关（F110-B Phase 3，默认关闭） */
    private boolean manualEndpointEnabled = false;

    /** 导入去重与更新配置（F104 2.9） */
    private Import importCfg = new Import();

    /** 详情补充源（F115：Wikidata 短描述默认开；HTML 站点补充默认关） */
    private Detail detail = new Detail();

    /** 爬取图片入 MinIO（F104 P1 导入联动，默认开启，失败降级保留原 URL） */
    private Image image = new Image();

    /** 流水线 MQ 开关（Phase 2 预留，默认 false 使用本地直连） */
    private boolean pipelineMqEnabled = false;

    /** 源/队列/配额选择（F110-B） */
    private Sources sources = new Sources();

    /** 错误重试（F110-B） */
    private Retry retry = new Retry();

    /** 熔断（F110-B，复用 travel-core CircuitBreaker） */
    private Circuit circuit = new Circuit();

    /** 执行队列类型（local=进程内+0/1 文件归档；redis=Redis Stream） */
    private String queueType = "local";

    /** 配额类型（local=内存；redis=Redis 持久） */
    private String quotaType = "local";

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

        /**
         * 城市名→adcode 列表（F109/F110-B：region 优先 adcode，规避中文关键词静默空）。
         * 用 List<Bean> 而非 Map<String,String>（规避 Spring Boot 3.5 非空 String Map 绑定回归）。
         */
        private List<CityAdcode> cityAdcodes = new ArrayList<>();

        /** typecode 前缀→业务标签列表（F110-B tags 推导，可配置覆盖） */
        private List<TypeTag> typeTagMap = new ArrayList<>();

        /** 周边搜索地址（备胎源） */
        private String aroundBaseUrl = "https://restapi.amap.com/v5/place/around";

        /** 周边搜索半径（米） */
        private int aroundRadius = 50_000;

        /** 便捷访问：city -> adcode */
        public Map<String, String> cityAdcodeMap() {
            Map<String, String> m = new HashMap<>();
            for (CityAdcode c : cityAdcodes) {
                if (c.getCity() != null && c.getAdcode() != null) {
                    m.put(c.getCity(), c.getAdcode());
                }
            }
            return m;
        }

        /** 便捷访问：typecode 前缀 -> tags */
        public Map<String, List<String>> typeTagMapAsMap() {
            Map<String, List<String>> m = new HashMap<>();
            for (TypeTag t : typeTagMap) {
                if (t.getTypeCode() != null) {
                    m.put(t.getTypeCode(), t.getTags() == null ? List.of() : t.getTags());
                }
            }
            return m;
        }

        @Data
        public static class CityAdcode {
            private String city;
            private String adcode;
        }

        @Data
        public static class TypeTag {
            private String typeCode;
            private List<String> tags = new ArrayList<>();
        }
    }

    @Data
    public static class Import {
        /** 爬虫刷新是否启用 upsert（true=已存在按 name+city 更新） */
        private boolean updateExisting = true;

        /** 别名表（归一化去重）：规范名 → 别名列表，如 故宫博物院 → 故宫 */
        private Map<String, String> aliasMap = new HashMap<>();
    }

    @Data
    public static class Detail {
        /** 每批（单次管道 process 调用）最多补全条目数，控制单轮时长（默认 10） */
        private int maxItemsPerBatch = 10;

        /** Wikidata 短描述补充源（CC0，默认开） */
        private Wikidata wikidata = new Wikidata();

        /** HTML 站点补充源（默认关；仅未来合规站点接入时开启） */
        private Html html = new Html();
    }

    @Data
    public static class Wikidata {
        /** 补充源开关（F115，默认开） */
        private boolean enabled = true;

        /** Wikidata 服务基址（API 与实体数据均在此域名） */
        private String baseUrl = "https://www.wikidata.org";

        /** 官方要求：UA 必须含项目名与联系方式（WD 政策） */
        private String userAgent = "travel-planner-crawler/1.0 (contact: travel-planner@example.com)";

        /** 请求最小间隔（毫秒），Wikidata 官方要求 ≥1 req/s，默认 1.2s */
        private long minIntervalMs = 1200;

        /** 单请求超时（毫秒） */
        private int timeoutMs = 30_000;

        /** TCP 连接超时（毫秒）；wikidata.org 在部分网络下连接极慢，需放宽 */
        private int connectTimeoutMs = 20_000;

        /** wbsearchentities 返回候选上限 */
        private int maxSearchResults = 3;

        /** 本地 LRU 缓存开关 */
        private boolean cacheEnabled = true;

        /** 缓存容量 */
        private int cacheMaxSize = 256;

        /** 缓存 TTL（天），默认 7 天 */
        private int cacheTtlDays = 7;

        /**
         * JDK HttpClient 失败时是否降级用 curl 子进程（F115 实证：
         * 部分网络对 wikidata.org 的 Java TLS ClientHello 指纹丢包，curl/Schannel 可通）。
         */
        private boolean fallbackCurlEnabled = true;

        /** curl 可执行文件（Windows 自带 curl.exe；Linux 通常为 /usr/bin/curl） */
        private String curlPath = "curl";

        /** curl 单请求超时（毫秒） */
        private int curlTimeoutMs = 40_000;

        /**
         * 抓取模式：auto=JDK HttpClient 优先失败后 curl（默认）；curl=直接 curl（CN 网络推荐，
         * 规避 Java TLS 指纹丢包）；jdk=仅 JDK HttpClient（不降级）。
         */
        private String fetchMode = "auto";
    }

    @Data
    public static class Html {
        /** HTML 详情补充开关（默认关闭） */
        private boolean enabled = false;

        /** 详情检索地址模板（{name}/{city} 占位） */
        private String siteUrl = "";

        /** 单站请求最小间隔（毫秒），默认 2s */
        private long minIntervalMs = 2000;
    }

    @Data
    public static class Image {
        /** 抓取图片上传 MinIO 开关（经 knowledge /api/v1/files/images） */
        private boolean uploadEnabled = true;

        /** 单张图片最大字节数（默认 5MB） */
        private long maxBytes = 5L * 1024 * 1024;

        /** 下载/上传超时（毫秒，默认 15s） */
        private int timeoutMs = 15_000;

        /** 图片下载+转存并行度（F119，默认 4，范围 1~8） */
        private int parallelism = 4;
    }

    @Data
    public static class Sources {
        /** Mock 数据源开关（离线回归用） */
        private boolean mockEnabled = false;

        /** 周边搜索备胎源开关（默认关） */
        private boolean aroundEnabled = false;

        /** 城市中心坐标列表（city -> "lng,lat"，周边源用；List<Bean> 绑定） */
        private List<CityCenter> cityCenters = new ArrayList<>();

        /** 便捷访问：city -> location */
        public Map<String, String> cityCenterMap() {
            Map<String, String> m = new HashMap<>();
            for (CityCenter c : cityCenters) {
                if (c.getCity() != null && c.getLocation() != null) {
                    m.put(c.getCity(), c.getLocation());
                }
            }
            return m;
        }

        @Data
        public static class CityCenter {
            private String city;
            private String location;
        }
    }

    @Data
    public static class Retry {
        /** 失败重试次数（CUQPS/网络等，每次仍计配额） */
        private int maxRetries = 2;

        /** 退避基数（毫秒），按 1s/3s 指数递增 */
        private long retryBackoffBaseMs = 1000;
    }

    @Data
    public static class Circuit {
        /** 连续失败阈值 */
        private int failureThreshold = 3;

        /** 统计窗口（毫秒） */
        private long windowMs = 60_000;

        /** 熔断时长（毫秒） */
        private long openTimeoutMs = 15_000;
    }
}
