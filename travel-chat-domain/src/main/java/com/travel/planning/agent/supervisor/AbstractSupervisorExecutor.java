package com.travel.planning.agent.supervisor;

import com.travel.common.exception.BusinessException;
import com.travel.common.exception.ErrorCode;
import com.travel.planning.trace.TraceContext;
import com.travel.stream.service.TurnCancellation;

/**
 * Z-4d：Supervisor 双执行器模板基类（预算七件单点化）。
 *
 * <p>W-2b 在 Graph/Stream 两侧维护的对称段（token 台账字段 / 轮预算上限 / 重试准入裁决 /
 * scopeKey 推导）单点化至此——真实体自 {@link SupervisorGraphExecutor} 逐字搬运，
 * 与 {@link SupervisorStreamExecutor} 同名方法逐字节对账一致（仅 javadoc 措辞微差，
 * 见 Z-4d 对位表）。子类经继承接管七件（2/2 换基接线），W/X 批等价单测双份跑基类。</p>
 *
 * <p>可见性说明：turnBudgetCap 原两侧均为 private，基类放宽为包级（同包测试直连面，
 * 语义零变化）；turnTokenSpend/enforceRetryTurnBudget 保持既有包级可见。</p>
 */
abstract class AbstractSupervisorExecutor {

    protected final TokenUsageInterceptor tokenUsageInterceptor;

    protected AbstractSupervisorExecutor(TokenUsageInterceptor tokenUsageInterceptor) {
        this.tokenUsageInterceptor = tokenUsageInterceptor;
    }

    /** S-C2b：意图分级预算（缺省自给=未接线时等价内置档；Graph/Stream 同款模式，Z-4d 单点化） */
    com.travel.planning.config.ChatBudgetPresets budgetPresets = new com.travel.planning.config.ChatBudgetPresets();

    /**
     * S-C2b：意图分级预算注入（W-AR-1 修型：两侧 setter 逐字同构，Z-4d 单点化）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setBudgetPresets(com.travel.planning.config.ChatBudgetPresets budgetPresets) {
        this.budgetPresets = budgetPresets;
    }

    /** W-2b：重试轮跨轮 token 台账（key=scopeKey=clientMessageId 回退 requestId；E-48 规范 ConcurrentMap，轮 finally 清键；包级可见=单测直连） */
    final java.util.concurrent.ConcurrentHashMap<String, Long> turnTokenSpend = new java.util.concurrent.ConcurrentHashMap<>();

    /** W-2b：轮总预算上限——与预算门（BUDGET_MAX_TOKENS_KEY 写入处）同源 budgetPresets.resolve(budgetIntent).getMaxTokens()；budgetIntent 空返回 null=守卫跳过零行为。 */
    Long turnBudgetCap() {
        String intent = TraceContext.active() ? TraceContext.current().budgetIntent : null;
        if (intent == null || intent.isBlank()) {
            return null;
        }
        return (long) budgetPresets.resolve(intent).getMaxTokens();
    }

    /** W-2b：重试准入裁决（包级可见=单测直连）——turnKey 已耗 + 在途累计 &gt; cap 抛 ChatService:797 同款 40303；budgetIntent 空零行为。 */
    void enforceRetryTurnBudget(String spendKey, String requestId) {
        Long cap = turnBudgetCap();
        if (cap == null) {
            return;
        }
        long projected = turnTokenSpend.getOrDefault(spendKey, 0L)
                + tokenUsageInterceptor.peek(requestId)[2];
        if (projected > cap) {
            throw new BusinessException(ErrorCode.MODEL_QUOTA_EXCEEDED.code(),
                    ErrorCode.MODEL_QUOTA_EXCEEDED.message());
        }
    }

    /** W-2b/M6-42：scopeKey 推导（clientMessageId 非空优先，回退 requestId；两侧原内联推导逐字同构，Z-4d 单点化）。 */
    static String scopeKeyOf(TurnCancellation cancel, String requestId) {
        return cancel.clientMessageId() != null && !cancel.clientMessageId().isBlank()
                ? cancel.clientMessageId() : requestId;
    }
}
