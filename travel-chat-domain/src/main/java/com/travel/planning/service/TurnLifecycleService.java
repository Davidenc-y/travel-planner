package com.travel.planning.service;

/**
 * Z-4a：轮次生命周期服务接口（中断/清除断点/状态查询/最近可恢复轮次）。
 *
 * <p>AD-2a 接口化：实现见 {@link TurnLifecycleServiceImpl}（@Service 在 Impl）；
 * 公有方法签名自原实现类逐字提取（零语义变更）；T-5c 台账注入为包私有 setter，
 * 留守实现类不入公共契约。</p>
 */
public interface TurnLifecycleService {

    /**
     * M6-36：中断在途轮次（PENDING → FAILED + Redis 中断标记）。
     *
     * <p>断点快照由 runStream 在路由前写入；若中断发生时尚未写入（步骤 3~7），
     * 重试将按 FAILED 语义整体重跑。</p>
     */
    void interruptTurn(Long userId, String sessionId, String clientMessageId);

    /**
     * M6-36：清除断点（用户发新消息时前端调用；prepareStream 侧另有双保险）。
     */
    void clearBreakpoint(Long userId, String sessionId, String clientMessageId);

    /**
     * M6-42：查询轮次状态（前端刷新后恢复重试入口）。
     *
     * <p>resumable = INTERRUPTED 且断点快照仍存在（Redis 30min TTL 窗口内）。</p>
     */
    ChatService.TurnStatusResult getTurnStatus(Long userId, String sessionId, String clientMessageId);

    /**
     * M6-47：会话最近可恢复中断轮次（刷新/重进会话恢复重试入口）。
     */
    ChatService.LatestInterruptedTurn getLatestInterruptedTurn(Long userId, String sessionId);
}
