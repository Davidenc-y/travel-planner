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
                                     String budgetJson, String clientRequestId);

    /**
     * S-D0（A 案，2026-09-21 审计实施）：5 参旧签名默认委托 6 参（clientRequestId=null
     * → 实现方自行生成），既有调用方与直构测试零改动。
     */
    default Optional<Long> syncAfterPlanning(Long userId, String sessionId,
                                             String userInput, String routePlanJson,
                                             String budgetJson) {
        return syncAfterPlanning(userId, sessionId, userInput, routePlanJson, budgetJson, null);
    }

    /**
     * S-D0：按 clientRequestId 反查行程 id（回写"超时后查再决"通道，只读）。
     * 默认 empty；planning 实现查库，webflux 桥实现经 HTTP 查询端点。
     */
    default Optional<Long> findItineraryIdByClientRequestId(String clientRequestId) {
        return Optional.empty();
    }
}
