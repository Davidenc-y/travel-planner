package com.travel.planning.cancellation;

import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * R8.1：轮次状态机（无状态 {@code @Component}，与 cancellation 包现有组件风格一致；纯新增，暂不接线）。
 *
 * <p>状态对应关系：{@link TurnState#RUNNING}↔流执行中；{@link TurnState#CANCEL_REQUESTED}↔stop
 * 广播已收；{@link TurnState#RESUMED}↔ItineraryResumeCoordinator 续跑。合法迁移矩阵见
 * {@link #ALLOWED}，不接线不改 TurnCancellationRegistry / Redis 广播 / ResumeCoordinator
 * 任何现有代码。</p>
 */
@Component
public class TurnStateMachine {

    private static final Map<TurnState, Set<TurnState>> ALLOWED = Map.of(
            TurnState.RUNNING, EnumSet.of(TurnState.CANCEL_REQUESTED, TurnState.COMPLETED, TurnState.RESUMED),
            TurnState.CANCEL_REQUESTED, EnumSet.of(TurnState.CANCELLED, TurnState.COMPLETED),
            TurnState.CANCELLED, EnumSet.of(TurnState.RESUMED),
            TurnState.COMPLETED, EnumSet.noneOf(TurnState.class),
            TurnState.RESUMED, EnumSet.of(TurnState.CANCEL_REQUESTED, TurnState.COMPLETED));

    /**
     * 判断 from -> to 是否为合法迁移。
     */
    public boolean canTransition(TurnState from, TurnState to) {
        return ALLOWED.getOrDefault(from, EnumSet.noneOf(TurnState.class)).contains(to);
    }

    /**
     * 断言 from -> to 为合法迁移，非法则抛 {@link IllegalStateException}。
     */
    public void assertTransition(TurnState from, TurnState to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException("非法轮次状态迁移: " + from + " -> " + to);
        }
    }
}
