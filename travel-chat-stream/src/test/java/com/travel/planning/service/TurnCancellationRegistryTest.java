package com.travel.planning.service;

import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * M6-40：取消令牌与登记表单测。
 */
class TurnCancellationRegistryTest {

    @Test
    void cancel_disposesRegisteredSubscriptionAndThrowsOnCheck() {
        TurnCancellationRegistry registry = new TurnCancellationRegistry();
        TurnCancellation cancellation = registry.register("key1");
        Disposable disposable = mock(Disposable.class);
        cancellation.setDisposable(disposable);

        assertThat(registry.cancel("key1")).isTrue();

        verify(disposable).dispose();
        assertThat(cancellation.isCancelled()).isTrue();
        assertThatThrownBy(cancellation::throwIfCancelled)
                .isInstanceOf(TurnInterruptedException.class);
        registry.remove("key1");
        assertThat(registry.cancel("key1")).isFalse();
    }

    @Test
    void setDisposableAfterCancel_disposesImmediately() {
        TurnCancellation cancellation = new TurnCancellation();
        cancellation.cancel();
        Disposable disposable = mock(Disposable.class);

        cancellation.setDisposable(disposable);

        verify(disposable).dispose();
    }

    @Test
    void noop_neverCancelled() {
        assertThat(TurnCancellation.NOOP.cancel()).isFalse();
        assertThat(TurnCancellation.NOOP.isCancelled()).isFalse();
        TurnCancellation.NOOP.throwIfCancelled();
    }
}
