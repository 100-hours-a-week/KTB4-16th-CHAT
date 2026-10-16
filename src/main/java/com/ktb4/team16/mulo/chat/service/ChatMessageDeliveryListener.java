package com.ktb4.team16.mulo.chat.service;

import com.ktb4.team16.mulo.chat.event.ChatMessageDeliveryEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ChatMessageDeliveryListener {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageDeliveryListener.class);
    private static final String DESTINATION = "/queue/chat-messages";

    private final SimpMessagingTemplate messagingTemplate;

    public ChatMessageDeliveryListener(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void deliver(ChatMessageDeliveryEvent event) {
        for (Long userId : event.recipientUserIds()) {
            try {
                messagingTemplate.convertAndSendToUser(userId.toString(), DESTINATION, event.response());
            } catch (RuntimeException exception) {
                log.warn("커밋된 채팅 메시지를 사용자에게 전달하지 못했습니다. userId={}", userId);
            }
        }
    }
}
