package com.travel.planning.memory.longterm.behavior;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * M17-2/M17-3：行为画像配置（决策 D-Q2 两档开关）。
 *
 * <ul>
 *   <li>{@code compute-enabled}（默认 true）：重算+落表。观测期开启——聚合结果
 *       在表与日志中可见，供验证聚合正确性；false 时零重算零落表。</li>
 *   <li>{@code inject-enabled}（默认 false）：把【行为特征】段注入会话上下文。
 *       观测 3~5 天验证聚合无误后开启；false 时组装输出与现状逐字节等价。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.memory.behavior-profile")
public class BehaviorProfileProperties {

    /** 两档开关之一：重算与落表（观测期 true） */
    private boolean computeEnabled = true;

    /** 两档开关之二：注入【行为特征】段（验证后 true；红线：false 时行为逐字节等价） */
    private boolean injectEnabled = false;

    /** 滚动统计窗口（天） */
    private int statsWindowDays = 90;

    /** 重算结果新鲜期（分钟）：窗口内直接复用，不重复聚合 */
    private int staleAfterMinutes = 60;

    /** 可靠性门槛：窗口内行程数达到该值才视为可注入 */
    private int minTrips = 2;

    /** 可靠性门槛：窗口内会话数达到该值才视为可注入 */
    private int minSessions = 5;

    /** 注入段 token 预算上限（中文≈1 token/字；纳入画像段既有四档截断体系） */
    private int injectMaxTokens = 120;

    /** 单次重算读取 trace 行数上限（防大用户全表扫） */
    private int traceScanLimit = 2000;
}
