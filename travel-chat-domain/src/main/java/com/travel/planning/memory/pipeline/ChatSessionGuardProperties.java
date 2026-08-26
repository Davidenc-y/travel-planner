package com.travel.planning.memory.pipeline;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * M4-4/P1-1：会话状态守卫配置。
 *
 * <p>对应 yml：{@code travel.chat.session.reject-archived}——ARCHIVED 会话的
 * sendMessage 是否拒绝（40902）。COMPLETED 幂等重放始终豁免（已完成轮次应可重放）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.chat.session")
public class ChatSessionGuardProperties {

    /** ARCHIVED 会话拒绝新消息（默认开；关闭则回到"归档仅隐藏列表"的旧行为） */
    private boolean rejectArchived = true;
}
