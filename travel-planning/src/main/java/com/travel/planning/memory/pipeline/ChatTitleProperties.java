package com.travel.planning.memory.pipeline;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * M5-1：会话标题生成配置。
 *
 * <p>对应 yml：{@code travel.chat.title.max-length}——首条用户消息生成标题时，
 * 超过该长度截断并追加省略号（短消息全量保留，不引 LLM）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.chat.title")
public class ChatTitleProperties {

    /** 标题最大长度（字符） */
    private int maxLength = 20;
}
