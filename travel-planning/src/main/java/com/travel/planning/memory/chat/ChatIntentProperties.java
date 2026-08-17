package com.travel.planning.memory.chat;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对话意图分类配置（F85 第二步）。
 *
 * <p>对应 yml：{@code travel.chat.intent.*}；enabled=false 时回退原 supervisor 流程。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.chat.intent")
public class ChatIntentProperties {

    /** 总开关；false → 全部走原 supervisor 流程（回滚开关） */
    private boolean enabled = true;

    /** 分类结果 LRU 缓存容量（0 表示不缓存） */
    private int cacheSize = 256;

    /** 规则无法判定时是否用轻模型兜底；false → 直接回退 PLANNING */
    private boolean llmFallback = true;
}
