package com.ktb4.team16.mulo.chat.delivery;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

class ChatDeliveryLifecycleTest {
    @Test
    void closesOnlyOnceOnRepeatedFailureAndCannotRestartAfterStop() {
        var closed = new AtomicInteger();
        var state = new ChatDeliveryLifecycle(closed::incrementAndGet);
        assertThat(state.isReady()).isFalse();
        state.markReady(state.failureEpoch());
        state.onDeliveryFailure();
        state.onDeliveryFailure();
        assertThat(closed.get()).isEqualTo(1);
        assertThat(state.isReady()).isFalse();
        state.markReady(state.failureEpoch());
        state.stopped();
        assertThat(state.isStopped()).isTrue();
        state.markReady(state.failureEpoch());
        assertThat(state.isReady()).isFalse();
    }

    @Test
    void everyFailureRestartsStabilityEvenWhenAlreadyDegraded() {
        var closed = new AtomicInteger();
        var state = new ChatDeliveryLifecycle(closed::incrementAndGet);
        state.markReady(state.failureEpoch());
        state.onDeliveryFailure();
        long first = state.failureEpoch();
        state.onDeliveryFailure();
        assertThat(state.failureEpoch()).isGreaterThan(first);
        assertThat(closed.get()).isEqualTo(1);
    }

    @Test
    void staleProbeCannotPromoteAfterAnInterveningFailure() {
        var state = new ChatDeliveryLifecycle(() -> {});
        long observed = state.failureEpoch();
        state.onDeliveryFailure();
        assertThat(state.markReady(observed)).isFalse();
        assertThat(state.isReady()).isFalse();
        assertThat(state.markReady(state.failureEpoch())).isTrue();
    }
}
