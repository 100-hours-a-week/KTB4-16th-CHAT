package com.ktb4.team16.mulo.chat.service;

import com.ktb4.team16.mulo.chat.event.ChatParticipantLeftDeliveryEvent;
import com.ktb4.team16.mulo.chat.event.ChatParticipantJoinedDeliveryEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ChatRoomEventDeliveryListener {

    private static final Logger log = LoggerFactory.getLogger(ChatRoomEventDeliveryListener.class);

    private final ChatRoomEventDeliveryService deliveryService;

    public ChatRoomEventDeliveryListener(ChatRoomEventDeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void deliverParticipantLeft(ChatParticipantLeftDeliveryEvent event) {
        for (Long recipientUserId : event.recipientUserIds()) {
            try {
                deliveryService.deliverParticipantLeftIfActive(
                        event.response().chatRoomId(), recipientUserId, event.response());
            } catch (RuntimeException exception) {
                log.warn("커밋된 퇴장 이벤트를 사용자에게 전달하지 못했습니다. userId={}, cause={}",
                        recipientUserId, exception.getClass().getSimpleName());
            }
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void deliverParticipantJoined(ChatParticipantJoinedDeliveryEvent event) {
        for (Long recipientUserId : event.recipientUserIds()) {
            try {
                deliveryService.deliverParticipantJoinedIfActive(
                        event.response().chatRoomId(), recipientUserId, event.response());
            } catch (RuntimeException exception) {
                log.warn("커밋된 입장 이벤트를 사용자에게 전달하지 못했습니다. userId={}, cause={}",
                        recipientUserId, exception.getClass().getSimpleName());
            }
        }
    }
}
