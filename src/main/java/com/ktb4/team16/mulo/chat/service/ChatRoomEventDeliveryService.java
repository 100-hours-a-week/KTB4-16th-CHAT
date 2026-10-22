package com.ktb4.team16.mulo.chat.service;

import com.ktb4.team16.mulo.chat.dto.response.ChatParticipantLeftResponse;
import com.ktb4.team16.mulo.chat.dto.response.ChatParticipantJoinedResponse;
import com.ktb4.team16.mulo.chat.dto.response.ChatParticipantNicknameChangedResponse;
import com.ktb4.team16.mulo.chat.entity.ChatRoom;
import com.ktb4.team16.mulo.chat.entity.ChatRoomParticipant;
import com.ktb4.team16.mulo.chat.exception.ChatRoomAccessException;
import com.ktb4.team16.mulo.chat.exception.ChatRoomAccessException.Reason;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatRoomEventDeliveryService {

    private static final String DESTINATION = "/queue/chat-events";

    private final ChatRoomAccessService accessService;
    private final SimpMessagingTemplate messagingTemplate;

    public ChatRoomEventDeliveryService(
            ChatRoomAccessService accessService,
            SimpMessagingTemplate messagingTemplate
    ) {
        this.accessService = accessService;
        this.messagingTemplate = messagingTemplate;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deliverParticipantLeftIfActive(
            Long chatRoomId,
            Long recipientUserId,
            ChatParticipantLeftResponse response
    ) {
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deliverParticipantJoinedIfActive(
            Long chatRoomId,
            Long recipientUserId,
            ChatParticipantJoinedResponse response
    ) {
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deliverParticipantNicknameChangedIfActive(
            Long chatRoomId,
            Long recipientUserId,
            ChatParticipantNicknameChangedResponse response
    ) {
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

        ChatRoom room;
        try {
            room = accessService.findRoom(chatRoomId);
            accessService.rejectExpired(room);
        } catch (ChatRoomAccessException exception) {
            if (exception.getReason() == Reason.ROOM_NOT_FOUND || exception.getReason() == Reason.ROOM_EXPIRED) {
                return;
            }
            throw exception;
        }
        messagingTemplate.convertAndSendToUser(recipientUserId.toString(), DESTINATION, response);
    }
}
