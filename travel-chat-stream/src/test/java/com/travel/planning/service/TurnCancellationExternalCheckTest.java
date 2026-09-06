package com.travel.planning.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M6-44：取消令牌外部权威检查（跨实例 Redis 标记兜底）单测。
 */
class TurnCancellationExternalCheckTest {

    @Test
    void externalTrue_marksCancelledAndThrows() {
        TurnCancellation cancellation = new TurnCancellation("key1");
        cancellation.attachExternalCancelCheck(() -> true);

        assertThat(cancellation.isCancelled()).isTrue();
        assertThatThrownBy(cancellation::throwIfCancelled)
                .isInstanceOf(TurnInterruptedException.class);
    }

    @Test
    void externalFalse_notCancelled() {
        TurnCancellation cancellation = new TurnCancellation("key1");
        cancellation.attachExternalCancelCheck(() -> false);

        assertThat(cancellation.isCancelled()).isFalse();
        cancellation.throwIfCancelled();
    }

    @Test
    void externalCheckException_conservativelyNotCancelled() {
        TurnCancellation cancellation = new TurnCancellation("key1");
        cancellation.attachExternalCancelCheck(() -> {
            throw new IllegalStateException("redis down");
        });

        // Redis 抖动时保守放行，不误杀正常轮次
        assertThat(cancellation.isCancelled()).isFalse();
        cancellation.throwIfCancelled();
    }

    @Test
    void localCancel_takesPrecedenceOverExternalFalse() {
        TurnCancellation cancellation = new TurnCancellation("key1");
        cancellation.attachExternalCancelCheck(() -> false);
        cancellation.cancel();

        assertThat(cancellation.isCancelled()).isTrue();
    }

    @Test
    void noop_attachIsNoop() {
        TurnCancellation.NOOP.attachExternalCancelCheck(() -> true);

        assertThat(TurnCancellation.NOOP.isCancelled()).isFalse();
        TurnCancellation.NOOP.throwIfCancelled();
    }
}
