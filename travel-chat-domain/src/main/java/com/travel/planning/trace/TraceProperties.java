package com.travel.planning.trace;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 追溯配置（F89）。
 *
 * <p>对应 yml：{@code travel.trace.*}；enabled=false 时采集全链路 no-op，
 * 业务零影响。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.trace")
public class TraceProperties {

    /** 总开关 */
    private boolean enabled = true;

    /** 存储实现：mysql / log */
    private String store = "mysql";

    /** 异步缓冲队列上限（超过丢弃并告警） */
    private int bufferSize = 1024;

    /** 批量落库间隔（毫秒） */
    private long flushIntervalMs = 1000;

    /** 批量落库批次大小 */
    private int batchSize = 50;
}
