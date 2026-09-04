package com.travel.planning.map.amap;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 高德地图路线/地理编码配置（M12）。
 *
 * <p>密钥只允许来自环境变量 ${AMAP_WEB_API_KEY:} 或本地未提交配置，禁止明文入库。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.map.amap")
public class AmapMapProperties {

    private boolean enabled = true;

    /** 高德 Web 服务 Key（与 travel-crawl 同源环境变量）。 */
    private String webApiKey = "";

    private String routeBaseUrl = "https://restapi.amap.com/v3/direction";
    private String geocodeBaseUrl = "https://restapi.amap.com/v3/geocode/geo";

    private int timeoutMs = 8000;
    private int connectTimeoutMs = 5000;

    /** 内部请求 QPS 上限（默认 2，低于个人默认约 3 QPS）。 */
    private int maxQps = 2;
    private int maxRetries = 1;
    private int retryBackoffMs = 1000;

    private int routeDayQuota = 4500;
    private int routeMonthQuota = 120_000;
    private int geocodeDayQuota = 3000;
    private int geocodeMonthQuota = 30_000;

    private boolean cacheEnabled = true;
    private int routeCacheTtlDays = 30;
    private int geocodeCacheTtlDays = 90;
    private int maxSegmentsPerRequest = 40;

    /** 熔断参数（连续失败后短时降级）。 */
    private int circuitFailureThreshold = 3;
    private int circuitWindowMs = 60_000;
    private int circuitOpenMs = 30_000;

    public boolean keyConfigured() {
        return webApiKey != null && !webApiKey.isBlank();
    }
}
