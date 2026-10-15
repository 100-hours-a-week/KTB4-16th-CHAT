package com.ktb4.team16.mulo.chat.chat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ktb4.team16.mulo.chat.entity.ChatRoom;
import com.ktb4.team16.mulo.chat.entity.ChatRoomParticipant;
import com.ktb4.team16.mulo.chat.repository.ChatRoomParticipantRepository;
import com.ktb4.team16.mulo.chat.repository.ChatRoomRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChatRoomAccessServiceTest {

    private final ChatRoomRepository roomRepository = mock(ChatRoomRepository.class);
    private final ChatRoomParticipantRepository participantRepository = mock(ChatRoomParticipantRepository.class);
    private final ChatRoomAccessService service = new ChatRoomAccessService(roomRepository, participantRepository,
            Clock.fixed(Instant.parse("2026-10-14T14:59:59Z"), ZoneOffset.UTC));

    @Test
    void mapsMissingRoomAndParticipantToExistingNotFoundContract() {
        when(roomRepository.findById(1L)).thenReturn(Optional.empty());
        when(participantRepository.findByChatRoomChatRoomIdAndUserId(1L, 2L)).thenReturn(Optional.empty());
        when(participantRepository.findByChatRoomChatRoomIdAndUserIdForUpdate(1L, 2L)).thenReturn(Optional.empty());

        assertReason(ChatRoomAccessException.Reason.ROOM_NOT_FOUND, () -> service.findRoom(1L));
        assertReason(ChatRoomAccessException.Reason.PARTICIPANT_NOT_FOUND, () -> service.findParticipant(1L, 2L));
        assertReason(ChatRoomAccessException.Reason.PARTICIPANT_NOT_FOUND,
                () -> service.findParticipantForUpdate(1L, 2L));
    }

    @Test
    void preservesUnjoinedAndLeftParticipantContracts() {
        ChatRoomParticipant unjoined = mock(ChatRoomParticipant.class);
        when(unjoined.getJoinedAt()).thenReturn(null);
        when(unjoined.getLeftAt()).thenReturn(null);
        ChatRoomParticipant left = mock(ChatRoomParticipant.class);
        when(left.getJoinedAt()).thenReturn(LocalDateTime.parse("2026-10-14T10:00:00"));
        when(left.getLeftAt()).thenReturn(LocalDateTime.parse("2026-10-14T11:00:00"));

        assertReason(ChatRoomAccessException.Reason.PARTICIPANT_NOT_JOINED,
                () -> service.requireActiveParticipant(unjoined));
        assertReason(ChatRoomAccessException.Reason.PARTICIPANT_LEFT, () -> service.requireActiveParticipant(left));
    }

    @Test
    void considersRoomExpiredAtKstMidnight() {
        ChatRoom room = mock(ChatRoom.class);
        when(room.getExpiresAt()).thenReturn(LocalDateTime.parse("2026-10-15T00:00:00"));

        service.rejectExpired(room);
        ChatRoom atMidnight = mock(ChatRoom.class);
        when(atMidnight.getExpiresAt()).thenReturn(LocalDateTime.parse("2026-10-14T23:59:59"));
        assertReason(ChatRoomAccessException.Reason.ROOM_EXPIRED, () -> service.rejectExpired(atMidnight));
    }

    private void assertReason(ChatRoomAccessException.Reason reason,
                              org.junit.jupiter.api.function.Executable action) {
        assertThatThrownBy(action::execute)
                .isInstanceOf(ChatRoomAccessException.class)
                .satisfies(exception -> {
                    ChatRoomAccessException accessException = (ChatRoomAccessException) exception;
                    org.assertj.core.api.Assertions.assertThat(accessException.getReason()).isEqualTo(reason);
                });
    }
}
