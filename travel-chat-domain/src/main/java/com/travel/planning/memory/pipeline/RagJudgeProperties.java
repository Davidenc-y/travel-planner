package com.travel.planning.memory.pipeline;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * M4-5a：在线相关性 Judge 配置（planning 注入侧）。
 *
 * <p>对应 yml：{@code travel.rag.judge.enabled} / {@code travel.rag.judge.timeout-ms}。
 * 设计要点：</p>
 * <ul>
 *   <li><b>默认关</b>（enabled=false）：关闭时 compose 行为与现状完全一致，回归零风险；</li>
 *   <li><b>fail-open</b>：判定任何异常/超时/解析失败均按"两段相关"放行，不阻断主链路；</li>
 *   <li>仅 PLANNING/REFINE 意图启用，RECALL/PROFILE/CHAT/FUNCTIONAL 豁免（回顾类必用会话知识）。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.rag.judge")
public class RagJudgeProperties {

    /** Judge 开关（默认 false：不调用轻模型，行为与现状一致） */
    private boolean enabled = false;

    /** 单次判定的硬性超时（毫秒），超时即放行（fail-open） */
    private long timeoutMs = 3000;
}
