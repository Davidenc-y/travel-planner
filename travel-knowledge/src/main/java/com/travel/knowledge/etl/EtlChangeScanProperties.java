package com.travel.knowledge.etl;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RK-4：ETL 内容 hash 对账扫描配置（travel.etl.change-scan.*）。
 *
 * <p>注册方式对照同模块 EtlProperties 先例（@Data @Component @ConfigurationProperties）；
 * 三键默认值=期望终态，yml 键由 B-2 落盘（enabled 默认关，E-33 纯净）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.etl.change-scan")
public class EtlChangeScanProperties {

    /** RK-4：对账扫描总开关（默认关；RK-5 重灌时临时置 true，验证完回关） */
    private boolean enabled = false;

    /** 单轮对账扫描条数上限（默认 500，与 yml change-scan.batch-size 对齐） */
    private int batchSize = 500;

    /** 扫描调度间隔（毫秒，默认 24h=86400000） */
    private long intervalMs = 86_400_000L;
}
