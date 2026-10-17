package com.ktb4.team16.mulo.chat.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ktb4.team16.mulo.chat.dto.response.ChatMessageResponse;
import com.ktb4.team16.mulo.chat.entity.ChatRoomParticipant;
import com.ktb4.team16.mulo.chat.exception.ChatRoomAccessException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class ChatMessageEventDeliveryServiceTest {

    private static final Long CHAT_ROOM_ID = 30L;
    private static final Long RECIPIENT_USER_ID = 28L;

    private ChatRoomAccessService accessService;
    private SimpMessagingTemplate messagingTemplate;
    private ChatMessageEventDeliveryService service;

    @BeforeEach
    void setUp() {
        accessService = mock(ChatRoomAccessService.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        service = new ChatMessageEventDeliveryService(accessService, messagingTemplate);
    }

    @Test
    void deliversToAnActiveParticipantAfterLockingTheParticipantRow() {
        ChatRoomParticipant recipient = mock(ChatRoomParticipant.class);
        ChatMessageResponse response = response();
        when(accessService.findParticipantForUpdate(CHAT_ROOM_ID, RECIPIENT_USER_ID)).thenReturn(recipient);
        when(accessService.isActiveParticipant(recipient)).thenReturn(true);

        service.deliverIfActive(CHAT_ROOM_ID, RECIPIENT_USER_ID, response);

        verify(accessService).findParticipantForUpdate(CHAT_ROOM_ID, RECIPIENT_USER_ID);
        verify(messagingTemplate).convertAndSendToUser("28", "/queue/chat-messages", response);
    }

    @Test
    void doesNotDeliverToAFormerParticipant() {
        ChatRoomParticipant recipient = mock(ChatRoomParticipant.class);
        when(accessService.findParticipantForUpdate(CHAT_ROOM_ID, RECIPIENT_USER_ID)).thenReturn(recipient);
        when(accessService.isActiveParticipant(recipient)).thenReturn(false);

        service.deliverIfActive(CHAT_ROOM_ID, RECIPIENT_USER_ID, response());

        verify(messagingTemplate, never()).convertAndSendToUser("28", "/queue/chat-messages", response());
    }

    @Test
    void skipsDeliveryWhenTheParticipantRecordDoesNotExist() {
        when(accessService.findParticipantForUpdate(CHAT_ROOM_ID, RECIPIENT_USER_ID))
                .thenThrow(new ChatRoomAccessException(ChatRoomAccessException.Reason.PARTICIPANT_NOT_FOUND));

        service.deliverIfActive(CHAT_ROOM_ID, RECIPIENT_USER_ID, response());

        verify(messagingTemplate, never()).convertAndSendToUser("28", "/queue/chat-messages", response());
    }

    private ChatMessageResponse response() {
        return new ChatMessageResponse(
                "MESSAGE", CHAT_ROOM_ID, 101L, UUID.randomUUID(), 17L, "hello", OffsetDateTime.now()
        );
    }
}
