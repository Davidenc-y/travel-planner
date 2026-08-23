package com.travel.planning.memory.shortterm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 短期会话记忆配置（F55/B1）。
 *
 * <p>对应 yml：{@code travel.memory.shortterm.*}。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.memory.shortterm")
public class ShortTermMemoryProperties {

    /** 总开关；false 时退化为纯原文窗口（不生成摘要） */
    private boolean enabled = true;

    /** 历史注入最大轮数 */
    private int maxTurns = 10;

    /** 历史 token 预算上限（触发摘要的基准） */
    private int historyMaxTokens = 2000;

    /** 摘要触发阈值：历史 token ≥ historyMaxTokens * ratio 时启用摘要 */
    private double summaryThresholdRatio = 0.7;

    /** 摘要触发轮数下限：会话 user 轮数 ≥ 该值时启用摘要（F56 修复：仅凭 token 阈值可能永不触发） */
    private int summaryMinTurns = 6;

    /** 全量摘要输入字符安全上限（超长会话兜底，避免单次摘要 prompt 过大） */
    private int summaryMaxChars = 20000;

    /** LLM 摘要输出 token 上限（prompt 约束） */
    private int summaryMaxTokens = 800;

    /** 摘要输出硬上限（超限二次压缩/截断，F58/B1.2） */
    private int summaryHardMaxTokens = 800;

    /** 滚动摘要刷新轮数：会话每增加该轮数后重新压缩（F58/B1.2） */
    private int summaryRefreshTurns = 5;

    /** 语义保真校验开关（F58/B1.2） */
    private boolean summaryValidate = true;

    /** 超限/校验失败重试次数（F58/B1.2） */
    private int summaryRetryTimes = 1;

    /** 注入总输入 token 预算（画像+摘要/历史+当前问题，F58/B1.2） */
    private int inputMaxTokens = 4000;

    /** 画像段单独 token 预算（B3-4/F72：超限按重要性裁剪，避免画像吃满注入预算） */
    private int profileMaxTokens = 800;

    /** Redis 摘要 TTL（天），对齐会话生命周期 */
    private int summaryTtlDays = 7;

    /** 摘要模式下滑动窗口保留的最近轮数 */
    private int recentWindowTurns = 2;

    /**
     * M4-1a/P0-1：摘要写入是否走 Lua CAS 原子路径（版本冲突放弃写入）。
     * false 时回退旧的双 set 路径（回滚开关，行为与 F58 一致）。
     */
    private boolean casEnabled = true;

    /** M4-4/P1-1：close 收口同步等待上限（秒）；超时转后台完成，未落盘由补偿兜底 */
    private int finalizeSyncWaitSeconds = 15;
}
