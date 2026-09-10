package com.travel.planning.cancellation;

/**
 * R8.1：轮次状态显式枚举（纯新增，暂不接线）。
 *
 * <p>与现有语义的对应关系：{@link #RUNNING}↔流执行中；{@link #CANCEL_REQUESTED}↔stop
 * 广播已收；{@link #RESUMED}↔ItineraryResumeCoordinator 续跑。此前这些状态语义散落在
 * 注释与日志中，本枚举为中断/重试语义提供可引用的显式模型，为《02》阶段 C 事件化铺路。</p>
 */
public enum TurnState {
    /** 流执行中。 */
    RUNNING,
    /** stop 广播已收，等待取消落地。 */
    CANCEL_REQUESTED,
    /** 已取消。 */
    CANCELLED,
    /** 已完成（终态，无出边）。 */
    COMPLETED,
    /** 取消后经 ItineraryResumeCoordinator 续跑。 */
    RESUMED
}
