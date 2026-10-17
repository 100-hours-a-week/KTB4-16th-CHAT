package com.ktb4.team16.mulo.chat.service;

import com.ktb4.team16.mulo.chat.dto.response.ChatMessageResponse;
import com.ktb4.team16.mulo.chat.entity.ChatRoomParticipant;
import com.ktb4.team16.mulo.chat.exception.ChatRoomAccessException;
import com.ktb4.team16.mulo.chat.exception.ChatRoomAccessException.Reason;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatMessageEventDeliveryService {

    private static final String DESTINATION = "/queue/chat-messages";

    private final ChatRoomAccessService accessService;
    private final SimpMessagingTemplate messagingTemplate;

    public ChatMessageEventDeliveryService(
            ChatRoomAccessService accessService,
            SimpMessagingTemplate messagingTemplate
    ) {
        this.accessService = accessService;
        this.messagingTemplate = messagingTemplate;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deliverIfActive(Long chatRoomId, Long recipientUserId, ChatMessageResponse response) {
        ChatRoomParticipant recipient;
        try {
            recipient = accessService.findParticipantForUpdate(chatRoomId, recipientUserId);
        } catch (ChatRoomAccessException exception) {
            if (exception.getReason() == Reason.PARTICIPANT_NOT_FOUND) {
                return;
            }
            throw exception;
        }
        if (!accessService.isActiveParticipant(recipient)) {
            return;
        }
        messagingTemplate.convertAndSendToUser(recipientUserId.toString(), DESTINATION, response);
    }
}
