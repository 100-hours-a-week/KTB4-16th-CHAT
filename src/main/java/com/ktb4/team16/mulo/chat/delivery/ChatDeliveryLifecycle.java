package com.ktb4.team16.mulo.chat.delivery;

/** Redis 전달 준비와 장애 상태를 관리한다. */
public final class ChatDeliveryLifecycle {
    public enum State {
        STARTING,
        READY,
        DEGRADED,
        STOPPED
    }

    private volatile State state = State.STARTING;
    private volatile long failureEpoch;
    private final Runnable closeSockets;

    /** 전달 장애 때 로컬 소켓을 복구용으로 종료하는 책임을 주입한다. */
    public ChatDeliveryLifecycle(Runnable closeSockets) {
        this.closeSockets = closeSockets;
    }

    /** 새 연결과 SEND를 받을 수 있는지 반환한다. */
    public boolean isReady() {
        return state == State.READY;
    }

    /** 자원 정리 후 상태 점검이 다시 연결을 열지 못하게 한다. */
    public boolean isStopped() {
        return state == State.STOPPED;
    }

    /** 현재 전달 상태를 반환한다. */
    public State state() {
        return state;
    }

    /** 마지막 점검 이후 추가 장애 발생 여부를 비교할 번호를 반환한다. */
    public long failureEpoch() {
        return failureEpoch;
    }

    /** 점검 중 새 장애가 없고 종료되지 않은 경우에만 입력을 허용한다. */
    public synchronized boolean markReady(long observedFailureEpoch) {
        if (isStopped() || failureEpoch != observedFailureEpoch) return false;
        state = State.READY;
        return true;
    }

    /** 반복 장애를 기록하되 소켓 종료는 정상 상태에서 이탈할 때만 실행한다. */
    public void onDeliveryFailure() {
        boolean close;
        synchronized (this) {
            close = state == State.READY;
            if (isStopped()) return;
            state = State.DEGRADED;
            failureEpoch++;
        }
        if (close) closeSockets.run();
    }

    /** 종료된 전달 자원을 다시 활성화하지 않는다. */
    public synchronized void stopped() {
        state = State.STOPPED;
    }
}
