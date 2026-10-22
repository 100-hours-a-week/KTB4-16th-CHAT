package com.ktb4.team16.mulo.chat.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ktb4.team16.mulo.chat.dto.response.ChatParticipantNicknameChangedResponse;
import com.ktb4.team16.mulo.chat.entity.ChatRoom;
import com.ktb4.team16.mulo.chat.entity.ChatRoomParticipant;
import com.ktb4.team16.mulo.chat.exception.ChatRoomAccessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class ChatParticipantNicknameEventDeliveryTest {

    private static final Long ROOM_ID = 101L;
    private static final Long USER_ID = 28L;

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
    void deliversNicknameEventToAnActiveParticipantInAnUnexpiredRoom() {
        ChatRoomParticipant participant = mock(ChatRoomParticipant.class);
        ChatRoom room = mock(ChatRoom.class);
        ChatParticipantNicknameChangedResponse response = response();
        when(accessService.findParticipantForUpdate(ROOM_ID, USER_ID)).thenReturn(participant);
        when(accessService.isActiveParticipant(participant)).thenReturn(true);
        when(accessService.findRoom(ROOM_ID)).thenReturn(room);

        service.deliverParticipantNicknameChangedIfActive(ROOM_ID, USER_ID, response);

        verify(accessService).rejectExpired(room);
        verify(messagingTemplate).convertAndSendToUser("28", "/queue/chat-events", response);
    }

    @Test
    void doesNotDeliverToInactiveParticipant() {
        ChatRoomParticipant participant = mock(ChatRoomParticipant.class);
        when(accessService.findParticipantForUpdate(ROOM_ID, USER_ID)).thenReturn(participant);
        when(accessService.isActiveParticipant(participant)).thenReturn(false);

        service.deliverParticipantNicknameChangedIfActive(ROOM_ID, USER_ID, response());

        verify(accessService, never()).findRoom(ROOM_ID);
        verify(messagingTemplate, never()).convertAndSendToUser("28", "/queue/chat-events", response());
    }

    @Test
    void doesNotDeliverToExpiredRoom() {
        ChatRoomParticipant participant = mock(ChatRoomParticipant.class);
        ChatRoom room = mock(ChatRoom.class);
        when(accessService.findParticipantForUpdate(ROOM_ID, USER_ID)).thenReturn(participant);
        when(accessService.isActiveParticipant(participant)).thenReturn(true);
        when(accessService.findRoom(ROOM_ID)).thenReturn(room);
        org.mockito.Mockito.doThrow(new ChatRoomAccessException(ChatRoomAccessException.Reason.ROOM_EXPIRED))
                .when(accessService).rejectExpired(room);

        service.deliverParticipantNicknameChangedIfActive(ROOM_ID, USER_ID, response());

        verify(messagingTemplate, never()).convertAndSendToUser("28", "/queue/chat-events", response());
    }

    private ChatParticipantNicknameChangedResponse response() {
        return new ChatParticipantNicknameChangedResponse(
                "PARTICIPANT_NICKNAME_CHANGED", ROOM_ID, 17L, "new-name");
    }
}
