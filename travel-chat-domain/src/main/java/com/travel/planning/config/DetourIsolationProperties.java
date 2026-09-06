package com.travel.planning.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * M24（E3，P-F 第二阶段）：偶然偏移（DETOUR）三闸门隔离配置。
 *
 * <p>D-V8-4 纪律：{@code enabled} 默认 <b>false</b>（观测先行——M23b 已接线
 * [ChatFocus] 观测日志，本开关在观测期达标后（≥30 样本/误判率&lt;5%/严重误伤=0）
 * 才改 true）；三个子闸门控制各写通道，主开关关闭时子闸门不生效
 * （分批开启/单闸回退均可）。</p>
 *
 * <ul>
 *   <li>闸门 1 {@code slice-skip}：DETOUR 轮跳过会话知识切片写入（ChatKnowledgeStep）</li>
 *   <li>闸门 2 {@code profile-skip}：DETOUR 轮跳过偏好落库（ChatPreferenceStep）</li>
 *   <li>闸门 3 {@code summary-filter}：摘要输入剔除 DETOUR 轮（SessionMemoryServiceImpl，
 *       确定性重评——以同一词表匹配器重评每条用户消息，无需持久化焦点标记）</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.chat.detour-isolation")
public class DetourIsolationProperties {

    /** 主开关（默认 false=观测模式，零隔离行为）。 */
    private boolean enabled = false;

    /** 闸门 1：DETOUR 轮跳过会话知识切片。 */
    private boolean sliceSkip = true;

    /** 闸门 2：DETOUR 轮跳过偏好落库。 */
    private boolean profileSkip = true;

    /** 闸门 3：摘要输入剔除 DETOUR 轮。 */
    private boolean summaryFilter = true;
}
