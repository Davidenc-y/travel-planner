package com.travel.knowledge.rag.support;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RK-9：来源权威裁决 tie-break 配置。
 *
 * <p>对应 yml 扁平键：{@code travel.rag.source-registry.tie-break-enabled} /
 * {@code travel.rag.source-registry.tie-break-epsilon}（与 SourceAuthorityRegistry 同前缀、
 * 各自绑定字段，Spring 多类同前缀绑定互不干扰）。enabled 默认关（E-33 纯净：默认零行为）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.rag.source-registry")
public class SourceRegistryTieBreakProperties {

    /** RK-9：tie-break 开关（默认关=原样返回字节等价；开启后相邻并列对按 authority 换位） */
    private boolean tieBreakEnabled = false;

    /** 分数相对差阈值（相邻两结果视为并列的 gap/max 上限，默认 0.05=5%） */
    private double tieBreakEpsilon = 0.05;
}
