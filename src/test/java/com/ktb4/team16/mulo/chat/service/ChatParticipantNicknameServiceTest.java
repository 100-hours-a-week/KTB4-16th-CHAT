package com.ktb4.team16.mulo.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ktb4.team16.mulo.chat.dto.response.ChatParticipantNicknameChangedResponse;
import com.ktb4.team16.mulo.chat.entity.ChatRoom;
import com.ktb4.team16.mulo.chat.entity.ChatRoomParticipant;
import com.ktb4.team16.mulo.chat.event.ChatParticipantNicknameChangedDeliveryEvent;
import com.ktb4.team16.mulo.chat.repository.ChatRoomParticipantRepository;
import com.ktb4.team16.mulo.chat.repository.ChatRoomRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class ChatParticipantNicknameServiceTest {

    private static final Long USER_ID = 17L;
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-10-22T03:00:00Z"), ZoneOffset.UTC);

    private ChatRoomParticipantRepository participantRepository;
    private ChatRoomRepository roomRepository;
    private ApplicationEventPublisher eventPublisher;
    private ChatParticipantNicknameService service;

    @BeforeEach
    void setUp() {
        participantRepository = mock(ChatRoomParticipantRepository.class);
        roomRepository = mock(ChatRoomRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new ChatParticipantNicknameService(participantRepository, roomRepository, eventPublisher, TEST_CLOCK);
    }

    @Test
    void updatesDirectAndGroupParticipantsAndPublishesToActiveParticipantsPerUnexpiredRoom() {
        ChatRoom directRoom = room(101L, LocalDateTime.parse("2026-10-23T00:00:00"));
        ChatRoom groupRoom = room(202L, LocalDateTime.parse("2026-10-23T00:00:00"));
        ChatRoom expiredRoom = room(303L, LocalDateTime.parse("2026-10-22T00:00:00"));
        ChatRoomParticipant directUser = participant(USER_ID, "old-direct", directRoom);
        ChatRoomParticipant groupUser = participant("old-group", groupRoom);
        ChatRoomParticipant expiredUser = participant("old-expired", expiredRoom);
        when(participantRepository.findAllByUserIdForUpdate(USER_ID))
                .thenReturn(List.of(directUser, groupUser, expiredUser));
        when(roomRepository.findAllById(any())).thenReturn(List.of(directRoom, groupRoom, expiredRoom));

        ChatRoomParticipant directPeer = participant(28L, directRoom);
        ChatRoomParticipant activeGroupPeer = participant(39L, groupRoom);
        ChatRoomParticipant activeChangedUser = participant(USER_ID, "new-name", groupRoom);
        when(participantRepository.findAllByChatRoomChatRoomIdInAndJoinedAtIsNotNullAndLeftAtIsNull(
                Set.of(101L, 202L, 303L))).thenReturn(
                List.of(directUser, directPeer, activeChangedUser, activeGroupPeer));

        var result = service.updateNickname(USER_ID, "new-name");

        assertThat(result.userId()).isEqualTo(USER_ID);
        assertThat(result.updatedParticipantCount()).isEqualTo(3);
        verify(directUser).changeNickname("new-name");
        verify(groupUser).changeNickname("new-name");
        verify(expiredUser).changeNickname("new-name");

        org.mockito.ArgumentCaptor<ChatParticipantNicknameChangedDeliveryEvent> captor =
                org.mockito.ArgumentCaptor.forClass(ChatParticipantNicknameChangedDeliveryEvent.class);
        verify(eventPublisher, org.mockito.Mockito.times(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(event -> event.response().chatRoomId())
                .containsExactly(101L, 202L);
        assertThat(captor.getAllValues())
                .extracting(ChatParticipantNicknameChangedDeliveryEvent::recipientUserIds)
                .containsExactly(List.of(USER_ID, 28L), List.of(USER_ID, 39L));
        assertThat(captor.getAllValues())
                .extracting(ChatParticipantNicknameChangedDeliveryEvent::response)
                .allSatisfy(response -> {
                    assertThat(response.type()).isEqualTo("PARTICIPANT_NICKNAME_CHANGED");
                    assertThat(response.nickname()).isEqualTo("new-name");
                    assertThat(response.userId()).isEqualTo(USER_ID);
                });
    }

    @Test
    void sameNicknameIsIdempotentAndDoesNotPublishAnEvent() {
        ChatRoom room = room(101L, LocalDateTime.parse("2026-10-23T00:00:00"));
        ChatRoomParticipant participant = participant("same-name", room);
        when(participantRepository.findAllByUserIdForUpdate(USER_ID)).thenReturn(List.of(participant));

        var result = service.updateNickname(USER_ID, "same-name");

        assertThat(result.updatedParticipantCount()).isZero();
        verify(participant, never()).changeNickname(any());
        verifyNoEventsOrFurtherReads();
    }

    @Test
    void userWithoutParticipantRowsSucceedsWithoutRoomLookupOrEvent() {
        when(participantRepository.findAllByUserIdForUpdate(USER_ID)).thenReturn(List.of());

        var result = service.updateNickname(USER_ID, "new-name");

        assertThat(result.userId()).isEqualTo(USER_ID);
        assertThat(result.updatedParticipantCount()).isZero();
        verifyNoEventsOrFurtherReads();
    }

    private void verifyNoEventsOrFurtherReads() {
        verify(eventPublisher, never()).publishEvent(any());
        verify(roomRepository, never()).findAllById(any());
        verify(participantRepository, never())
                .findAllByChatRoomChatRoomIdInAndJoinedAtIsNotNullAndLeftAtIsNull(any());
    }

    private ChatRoom room(Long roomId, LocalDateTime expiresAt) {
        ChatRoom room = mock(ChatRoom.class);
        when(room.getChatRoomId()).thenReturn(roomId);
        when(room.getExpiresAt()).thenReturn(expiresAt);
        return room;
    }

    private ChatRoomParticipant participant(String nickname, ChatRoom room) {
        ChatRoomParticipant participant = mock(ChatRoomParticipant.class);
        when(participant.getNickname()).thenReturn(nickname);
        when(participant.getChatRoom()).thenReturn(room);
        return participant;
    }

    private ChatRoomParticipant participant(Long userId, ChatRoom room) {
        ChatRoomParticipant participant = mock(ChatRoomParticipant.class);
        when(participant.getUserId()).thenReturn(userId);
        when(participant.getChatRoom()).thenReturn(room);
        return participant;
    }

    private ChatRoomParticipant participant(Long userId, String nickname, ChatRoom room) {
        ChatRoomParticipant participant = participant(nickname, room);
        when(participant.getUserId()).thenReturn(userId);
        return participant;
    }
}
