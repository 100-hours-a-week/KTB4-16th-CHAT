package com.ktb4.team16.mulo.chat.delivery;

import com.ktb4.team16.mulo.chat.dto.response.*;
import com.ktb4.team16.mulo.chat.service.*;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.messaging.simp.user.SimpUserRegistry;

import java.util.concurrent.*;

/** Redis 수신 스레드와 분리된 방별 직렬 처리기로 로컬 연결에만 보낸다. */
public final class ChatLocalDeliveryDispatcher {
    private final ThreadPoolExecutor[] stripes = new ThreadPoolExecutor[4];
    private final SimpUserRegistry users;
    private final ChatMessageEventDeliveryService messages;
    private final ChatRoomEventDeliveryService rooms;
    private final ChatDeliveryCodec codec;
    private final ChatDeliveryLifecycle lifecycle;
    private final MeterRegistry meters;

    /** 큐와 worker 수를 제한해 외부 장애가 무한 메모리 적체로 번지지 않게 한다. */
    public ChatLocalDeliveryDispatcher(
            SimpUserRegistry users,
            ChatMessageEventDeliveryService messages,
            ChatRoomEventDeliveryService rooms,
            ChatDeliveryCodec codec,
            ChatDeliveryLifecycle lifecycle,
            MeterRegistry meters) {
        this.users = users;
        this.messages = messages;
        this.rooms = rooms;
        this.codec = codec;
        this.lifecycle = lifecycle;
        this.meters = meters;
        for (int i = 0; i < 4; i++) {
            int index = i;
            stripes[i] =
                    new ThreadPoolExecutor(
                            1,
                            1,
                            0,
                            TimeUnit.MILLISECONDS,
                            new ArrayBlockingQueue<>(256),
                            r -> {
                                var t = new Thread(r, "chat-delivery-" + index);
                                t.setDaemon(true);
                                return t;
                            },
                            new ThreadPoolExecutor.AbortPolicy());
            meters.gauge(
                    "mulo.chat.delivery.queue",
                    java.util.List.of(
                            io.micrometer.core.instrument.Tag.of("stripe", String.valueOf(i))),
                    stripes[i],
                    e -> e.getQueue().size());
        }
    }

    /** 같은 방은 같은 stripe로 보내고 초과 시 복구를 유도한다. */
    public boolean submit(ChatDeliveryEnvelope event) {
        try {
            stripes[Math.floorMod(event.chatRoomId().hashCode(), 4)].execute(() -> deliver(event));
            return true;
        } catch (RejectedExecutionException e) {
            meters.counter("mulo.chat.delivery.rejected").increment();
            lifecycle.onDeliveryFailure();
            return false;
        }
    }

    /** 로컬 구독 확인 뒤 기존 DB 참가자 검사와 전송을 재사용한다. */
    private void deliver(ChatDeliveryEnvelope e) {
        try {
            Object response = codec.response(e);
            for (Long id : e.recipientUserIds().stream().distinct().toList()) {
                if (users.getUser(id.toString()) == null) continue;
                switch (response) {
                    case ChatMessageResponse r -> messages.deliverIfActive(e.chatRoomId(), id, r);
                    case ChatParticipantJoinedResponse r ->
                            rooms.deliverParticipantJoinedIfActive(e.chatRoomId(), id, r);
                    case ChatParticipantLeftResponse r ->
                            rooms.deliverParticipantLeftIfActive(e.chatRoomId(), id, r);
                    case ChatParticipantNicknameChangedResponse r ->
                            rooms.deliverParticipantNicknameChangedIfActive(e.chatRoomId(), id, r);
                    default -> throw new IllegalArgumentException("Unsupported payload");
                }
            }
        } catch (RuntimeException failure) {
            meters.counter("mulo.chat.delivery.failure").increment();
            lifecycle.onDeliveryFailure();
        }
    }

    /** Redis 연결 소유자 종료 시 로컬 전달 워커를 정리한다. */
    public void stop() {
        for (var stripe : stripes) stripe.shutdownNow();
    }

    /** 상태 복구 전 큐 적체 여부를 검사한다. */
    public boolean isIdle() {
        for (var stripe : stripes)
            if (!stripe.getQueue().isEmpty() || stripe.getActiveCount() > 0) return false;
        return true;
    }
}
