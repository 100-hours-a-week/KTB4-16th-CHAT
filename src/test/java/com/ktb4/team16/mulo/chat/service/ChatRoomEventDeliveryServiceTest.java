package com.ktb4.team16.mulo.chat.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ktb4.team16.mulo.chat.dto.response.ChatParticipantLeftResponse;
import com.ktb4.team16.mulo.chat.dto.response.ChatParticipantJoinedResponse;
import com.ktb4.team16.mulo.chat.entity.ChatRoomParticipant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class ChatRoomEventDeliveryServiceTest {

    private static final Long CHAT_ROOM_ID = 30L;
    private static final Long RECIPIENT_USER_ID = 28L;

    private ChatRoomAccessService accessService;
    private SimpMessagingTemplate messagingTemplate;
    private ChatRoomEventDeliveryService service;

    @BeforeEach
    void setUp() {
        accessService = mock(ChatRoomAccessService.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        service = new ChatRoomEventDeliveryService(accessService, messagingTemplate);
    }

    @Test
    void deliversOnlyToAnActiveParticipantUsingTheUserEventQueue() {
        ChatRoomParticipant recipient = mock(ChatRoomParticipant.class);
        ChatParticipantLeftResponse response = response();
        when(accessService.findParticipantForUpdate(CHAT_ROOM_ID, RECIPIENT_USER_ID)).thenReturn(recipient);
        when(accessService.isActiveParticipant(recipient)).thenReturn(true);

        service.deliverParticipantLeftIfActive(CHAT_ROOM_ID, RECIPIENT_USER_ID, response);

        verify(messagingTemplate).convertAndSendToUser("28", "/queue/chat-events", response);
    }

    @Test
    void doesNotDeliverToAParticipantWhoHasAlreadyLeft() {
        ChatRoomParticipant recipient = mock(ChatRoomParticipant.class);
        when(accessService.findParticipantForUpdate(CHAT_ROOM_ID, RECIPIENT_USER_ID)).thenReturn(recipient);
        when(accessService.isActiveParticipant(recipient)).thenReturn(false);

        service.deliverParticipantLeftIfActive(CHAT_ROOM_ID, RECIPIENT_USER_ID, response());

        verify(messagingTemplate, never()).convertAndSendToUser("28", "/queue/chat-events", response());
    }

    @Test
    void deliversJoinedEventOnlyToAnActiveParticipantUsingTheUserEventQueue() {
        ChatRoomParticipant recipient = mock(ChatRoomParticipant.class);
        ChatParticipantJoinedResponse response = joinedResponse();
        when(accessService.findParticipantForUpdate(CHAT_ROOM_ID, RECIPIENT_USER_ID)).thenReturn(recipient);
        when(accessService.isActiveParticipant(recipient)).thenReturn(true);

        service.deliverParticipantJoinedIfActive(CHAT_ROOM_ID, RECIPIENT_USER_ID, response);

        verify(messagingTemplate).convertAndSendToUser("28", "/queue/chat-events", response);
    }

    @Test
    void doesNotDeliverJoinedEventToAFormerParticipant() {
        ChatRoomParticipant recipient = mock(ChatRoomParticipant.class);
        when(accessService.findParticipantForUpdate(CHAT_ROOM_ID, RECIPIENT_USER_ID)).thenReturn(recipient);
        when(accessService.isActiveParticipant(recipient)).thenReturn(false);

        service.deliverParticipantJoinedIfActive(CHAT_ROOM_ID, RECIPIENT_USER_ID, joinedResponse());

        verify(messagingTemplate, never()).convertAndSendToUser("28", "/queue/chat-events", joinedResponse());
    }

    private ChatParticipantLeftResponse response() {
        return new ChatParticipantLeftResponse(
                "PARTICIPANT_LEFT", CHAT_ROOM_ID, 17L, "sender",
                OffsetDateTime.of(2026, 10, 14, 15, 0, 0, 0, ZoneOffset.ofHours(9))
        );
    }

    private ChatParticipantJoinedResponse joinedResponse() {
        return new ChatParticipantJoinedResponse(
                "PARTICIPANT_JOINED", CHAT_ROOM_ID, 17L, "sender", "A", 2,
                OffsetDateTime.of(2026, 10, 14, 15, 0, 0, 0, ZoneOffset.ofHours(9))
        );
    }
}
