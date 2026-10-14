package com.ktb4.team16.mulo.chat.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ktb4.team16.mulo.chat.dto.response.ChatMessageHistoryResponse;
import com.ktb4.team16.mulo.chat.entity.ChatMessage;
import com.ktb4.team16.mulo.chat.entity.ChatRoom;
import com.ktb4.team16.mulo.chat.entity.ChatRoomParticipant;
import com.ktb4.team16.mulo.chat.exception.ChatApiException;
import com.ktb4.team16.mulo.chat.repository.ChatMessageRepository;
import com.ktb4.team16.mulo.chat.repository.ChatRoomParticipantRepository;
import com.ktb4.team16.mulo.chat.repository.ChatRoomRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

class ChatRoomRestServiceTest {

    private static final long CHAT_ROOM_ID = 30L;
    private static final long USER_ID = 17L;

    private ChatRoomRepository chatRoomRepository;
    private ChatRoomParticipantRepository participantRepository;
    private ChatMessageRepository messageRepository;
    private ChatRoomRestService service;

    @BeforeEach
    void setUp() {
        chatRoomRepository = mock(ChatRoomRepository.class);
        participantRepository = mock(ChatRoomParticipantRepository.class);
        messageRepository = mock(ChatMessageRepository.class);
        service = new ChatRoomRestService(chatRoomRepository, participantRepository, messageRepository);
    }

    @Test
    void entersEligibleParticipantAndMakesRepeatedEntryIdempotent() {
        ChatRoomParticipant participant = participant(null, null);
        arrangeRoomAndParticipant(activeRoom(), participant);

        service.enter(CHAT_ROOM_ID, USER_ID);
        service.enter(CHAT_ROOM_ID, USER_ID);

        verify(participant, org.mockito.Mockito.times(2)).enter();
        verify(participantRepository, org.mockito.Mockito.times(2))
                .findByChatRoomChatRoomIdAndUserIdForUpdate(CHAT_ROOM_ID, USER_ID);
    }

    @Test
    void rejectsEntryForMissingRoomNonParticipantExpiredRoomAndFormerParticipant() {
        when(chatRoomRepository.findById(CHAT_ROOM_ID)).thenReturn(Optional.empty());
        assertCode(HttpStatus.NOT_FOUND, "CHAT_ROOM_NOT_FOUND", () -> service.enter(CHAT_ROOM_ID, USER_ID));

        ChatRoom activeRoom = activeRoom();
        when(chatRoomRepository.findById(CHAT_ROOM_ID)).thenReturn(Optional.of(activeRoom));
        when(participantRepository.findByChatRoomChatRoomIdAndUserId(CHAT_ROOM_ID, USER_ID)).thenReturn(Optional.empty());
        assertCode(HttpStatus.NOT_FOUND, "CHAT_ROOM_NOT_FOUND", () -> service.enter(CHAT_ROOM_ID, USER_ID));

        arrangeRoomAndParticipant(expiredRoom(), participant(null, null));
        assertCode(HttpStatus.FORBIDDEN, "CHAT_ROOM_EXPIRED", () -> service.enter(CHAT_ROOM_ID, USER_ID));

        arrangeRoomAndParticipant(activeRoom(), participant(null, LocalDateTime.now()));
        assertCode(HttpStatus.FORBIDDEN, "CHAT_REENTRY_FORBIDDEN", () -> service.enter(CHAT_ROOM_ID, USER_ID));
    }

    @Test
    void leavesActiveParticipantAndRejectsDuplicateLeave() {
        ChatRoomParticipant participant = participant(LocalDateTime.now(), null);
        arrangeRoomAndParticipant(activeRoom(), participant);

        service.leave(CHAT_ROOM_ID, USER_ID);

        verify(participant).leave();
        verify(participantRepository).findByChatRoomChatRoomIdAndUserIdForUpdate(CHAT_ROOM_ID, USER_ID);

        arrangeRoomAndParticipant(activeRoom(), participant(LocalDateTime.now(), LocalDateTime.now()));
        assertCode(HttpStatus.CONFLICT, "CHAT_ALREADY_LEFT", () -> service.leave(CHAT_ROOM_ID, USER_ID));
    }

    @Test
    void rejectsLeaveForNonParticipantAndExpiredRoomWithoutChangingState() {
        ChatRoom activeRoom = activeRoom();
        when(chatRoomRepository.findById(CHAT_ROOM_ID)).thenReturn(Optional.of(activeRoom));
        when(participantRepository.findByChatRoomChatRoomIdAndUserId(CHAT_ROOM_ID, USER_ID)).thenReturn(Optional.empty());
        assertCode(HttpStatus.NOT_FOUND, "CHAT_ROOM_NOT_FOUND", () -> service.leave(CHAT_ROOM_ID, USER_ID));

        ChatRoomParticipant participant = participant(LocalDateTime.now(), null);
        arrangeRoomAndParticipant(expiredRoom(), participant);
        assertCode(HttpStatus.FORBIDDEN, "CHAT_ROOM_EXPIRED", () -> service.leave(CHAT_ROOM_ID, USER_ID));
        verify(participant, never()).leave();
    }

    @Test
    void readsEmptyMessageHistoryForActiveParticipant() {
        arrangeRoomAndParticipant(activeRoom(), participant(LocalDateTime.now(), null));
        when(messageRepository.findHistory(eq(CHAT_ROOM_ID), any(), any(), any(Pageable.class)))
                .thenReturn(new SliceImpl<>(List.of()));
        when(participantRepository.findAllByChatRoomChatRoomId(CHAT_ROOM_ID)).thenReturn(List.of());

        ChatMessageHistoryResponse response = service.getHistory(CHAT_ROOM_ID, USER_ID, null);

        assertThat(response.messages()).isEmpty();
        assertThat(response.nextCursor()).isNull();
        assertThat(response.hasNext()).isFalse();
    }

    @Test
    void returnsMessagesOldestFirstWithStableSameTimestampOrderingAndCursor() {
        LocalDateTime timestamp = LocalDateTime.of(2026, 10, 13, 15, 30);
        ChatMessage newest = message(302L, timestamp, USER_ID, "second");
        ChatMessage oldest = message(301L, timestamp, USER_ID, "first");
        ChatRoomParticipant user = participant(LocalDateTime.now(), null);
        when(user.getUserId()).thenReturn(USER_ID);
        when(user.getNickname()).thenReturn("jettie");
        arrangeRoomAndParticipant(activeRoom(), user);
        when(messageRepository.findHistory(eq(CHAT_ROOM_ID), any(), any(), any(Pageable.class)))
                .thenReturn(new SliceImpl<>(List.of(newest, oldest), Pageable.ofSize(20), true));
        when(participantRepository.findAllByChatRoomChatRoomId(CHAT_ROOM_ID)).thenReturn(List.of(user));

        ChatMessageHistoryResponse response = service.getHistory(CHAT_ROOM_ID, USER_ID, null);

        assertThat(response.messages()).extracting(ChatMessageHistoryResponse.Message::chatMessageId)
                .containsExactly(301L, 302L);
        assertThat(response.messages().getFirst().createdAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(response.messages().getFirst().createdAt().toLocalDateTime()).isEqualTo(timestamp);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.nextCursor()).isNotBlank();
    }

    @Test
    void rejectsInvalidCursorAndHistoryAccessByFormerOrNotYetJoinedParticipant() {
        arrangeRoomAndParticipant(activeRoom(), participant(LocalDateTime.now(), null));
        assertCode(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", () -> service.getHistory(CHAT_ROOM_ID, USER_ID, "invalid"));
        verify(messageRepository, never()).findHistory(any(), any(), any(), any());

        arrangeRoomAndParticipant(activeRoom(), participant(LocalDateTime.now(), LocalDateTime.now()));
        assertCode(HttpStatus.FORBIDDEN, "CHAT_ALREADY_LEFT", () -> service.getHistory(CHAT_ROOM_ID, USER_ID, null));

        arrangeRoomAndParticipant(activeRoom(), participant(null, null));
        assertCode(HttpStatus.NOT_FOUND, "CHAT_ROOM_NOT_FOUND", () -> service.getHistory(CHAT_ROOM_ID, USER_ID, null));
    }

    @Test
    void rejectsMalformedCursorDateMissingPartsAndInvalidMessageId() {
        arrangeRoomAndParticipant(activeRoom(), participant(LocalDateTime.now(), null));
        for (String cursorValue : List.of("not-a-date|1", "2026-10-13T15:30:00", "2026-10-13T15:30:00|0",
                "2026-10-13T15:30:00|-1", "2026-10-13T15:30:00|999999999999999999999")) {
            String cursor = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(cursorValue.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertCode(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", () -> service.getHistory(CHAT_ROOM_ID, USER_ID, cursor));
        }
        verify(messageRepository, never()).findHistory(any(), any(), any(), any());
    }

    @Test
    void serializesMessageCreatedAtWithKstOffset() throws Exception {
        ChatMessageHistoryResponse response = new ChatMessageHistoryResponse(
                List.of(new ChatMessageHistoryResponse.Message(
                        301L, "550e8400-e29b-41d4-a716-446655440000", USER_ID, "jettie", "안녕하세요",
                        LocalDateTime.of(2026, 10, 13, 15, 30).atOffset(ZoneOffset.ofHours(9)))),
                null,
                false
        );

        String json = new ObjectMapper().writeValueAsString(response);

        assertThat(json).contains("\"createdAt\":\"2026-10-13T15:30:00+09:00\"");
    }

    private void arrangeRoomAndParticipant(ChatRoom room, ChatRoomParticipant participant) {
        when(chatRoomRepository.findById(CHAT_ROOM_ID)).thenReturn(Optional.of(room));
        when(participantRepository.findByChatRoomChatRoomIdAndUserId(CHAT_ROOM_ID, USER_ID))
                .thenReturn(Optional.of(participant));
        when(participantRepository.findByChatRoomChatRoomIdAndUserIdForUpdate(CHAT_ROOM_ID, USER_ID))
                .thenReturn(Optional.of(participant));
    }

    private ChatRoom activeRoom() {
        ChatRoom room = mock(ChatRoom.class);
        when(room.getExpiresAt()).thenReturn(LocalDateTime.of(2999, 1, 1, 0, 0));
        return room;
    }

    private ChatRoom expiredRoom() {
        ChatRoom room = mock(ChatRoom.class);
        when(room.getExpiresAt()).thenReturn(LocalDateTime.of(2000, 1, 1, 0, 0));
        return room;
    }

    private ChatRoomParticipant participant(LocalDateTime joinedAt, LocalDateTime leftAt) {
        ChatRoomParticipant participant = mock(ChatRoomParticipant.class);
        when(participant.getJoinedAt()).thenReturn(joinedAt);
        when(participant.getLeftAt()).thenReturn(leftAt);
        return participant;
    }

    private ChatMessage message(Long id, LocalDateTime createdAt, Long userId, String content) {
        ChatMessage message = mock(ChatMessage.class);
        when(message.getChatMessageId()).thenReturn(id);
        when(message.getCreatedAt()).thenReturn(createdAt);
        when(message.getUserId()).thenReturn(userId);
        when(message.getClientMessageId()).thenReturn("550e8400-e29b-41d4-a716-446655440000");
        when(message.getContent()).thenReturn(content);
        return message;
    }

    private void assertCode(HttpStatus status, String code, org.junit.jupiter.api.function.Executable action) {
        assertThatThrownBy(action::execute)
                .isInstanceOf(ChatApiException.class)
                .satisfies(exception -> {
                    ChatApiException chatException = (ChatApiException) exception;
                    assertThat(chatException.getStatus()).isEqualTo(status);
                    assertThat(chatException.getCode()).isEqualTo(code);
                });
    }
}
