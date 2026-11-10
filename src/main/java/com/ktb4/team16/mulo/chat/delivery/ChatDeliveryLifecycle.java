package com.ktb4.team16.mulo.chat.delivery;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/** 입력 gate와 실행 중 SEND 등록을 같은 모니터로 보호한다. */
public final class ChatDeliveryLifecycle {
    public enum State {
        STARTING,
        READY,
        DEGRADED,
        DRAINING,
        STOPPED
    }

    private volatile State state = State.STARTING;
    private int activeSends;
    private volatile long failureEpoch;
    private final Runnable closeSockets;

    /** 로컬 소켓을 복구용으로 종료하는 책임을 주입한다. */
    public ChatDeliveryLifecycle(Runnable closeSockets) {
        this.closeSockets = closeSockets;
    }

    public boolean isReady() {
        return state == State.READY;
    }

    public boolean isDraining() {
        return state == State.DRAINING || state == State.STOPPED;
    }

    public State state() {
        return state;
    }

    public long failureEpoch() {
        return failureEpoch;
    }

    /** 구독 및 발행 경로가 안정된 경우에만 입력을 허용한다. */
    public synchronized boolean markReady(long observedFailureEpoch) {
        if (isDraining() || failureEpoch != observedFailureEpoch) return false;
        state = State.READY;
        return true;
    }

    /** 동일 장애 상태에서 소켓 종료를 반복하지 않는다. */
    public void onDeliveryFailure() {
        boolean close;
        synchronized (this) {
            close = state == State.READY;
            if (isDraining()) return;
            state = State.DEGRADED;
            failureEpoch++;
        }
        if (close) closeSockets.run();
    }

    /** 종료 전환 후에는 복구 신호로 다시 READY가 되지 않는다. */
    public void beginDrain() {
        synchronized (this) {
            if (isDraining()) return;
            state = State.DRAINING;
        }
        closeSockets.run();
    }

    /** 입력 허용 검사와 진행 작업 등록을 원자적으로 수행한다. */
    public synchronized SendLease acquireSend() {
        if (!isReady()) throw new IllegalStateException("Chat delivery unavailable");
        activeSends++;
        return new SendLease();
    }

    public synchronized int inFlight() {
        return activeSends;
    }

    /** 커밋 후 발행까지 포함한 SEND 완료를 제한 시간 동안 기다린다. */
    public synchronized boolean awaitSends(Duration timeout) {
        long end = System.nanoTime() + timeout.toNanos();
        while (activeSends > 0) {
            long remaining = end - System.nanoTime();
            if (remaining <= 0) return false;
            try {
                java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this, remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** 종료가 완료된 자원을 다시 활성화하지 않는다. */
    public synchronized void stopped() {
        state = State.STOPPED;
    }

    /** 같은 SEND의 중복 정리로 카운터가 음수가 되지 않게 한다. */
    public final class SendLease implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public void close() {
            if (closed.compareAndSet(false, true))
                synchronized (ChatDeliveryLifecycle.this) {
                    activeSends--;
                    ChatDeliveryLifecycle.this.notifyAll();
                }
        }
    }
}
