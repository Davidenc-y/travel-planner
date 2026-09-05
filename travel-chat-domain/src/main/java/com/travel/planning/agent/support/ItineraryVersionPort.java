package com.travel.planning.agent.support;

import java.util.Optional;

/**
 * M13-2b：行程版本回写端口（chat-domain 定义，travel-planning 实现注入）。
 *
 * <p>复刻 {@link ItineraryConflictPort} 中立端口范式，避免 chat-domain 反向依赖
 * travel-planning。回写失败一律静默降级（观测优先，不阻断回答）。</p>
 */
public interface ItineraryVersionPort {

    /**
     * 规划/REFINE 成功后同步行程资产：
     * ① 会话已关联行程 → REFINE 终态回写（updateCompleted，自动建版 + diff）；
     * ② 未关联且 create-on-chat 开启 → 从用户输入解析元数据创建新行程。
     *
     * @return 关联/新建的 itineraryId；无可回写/解析失败/异常 → Optional.empty()
     */
    Optional<Long> syncAfterPlanning(Long userId, String sessionId,
                                     String userInput, String routePlanJson,
                                     String budgetJson);
}
