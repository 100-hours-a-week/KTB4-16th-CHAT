package com.ktb4.team16.mulo.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ktb4.team16.mulo.chat.dto.request.CreateChatRoomRequest;
import com.ktb4.team16.mulo.chat.dto.response.CreateChatRoomResponse;
import com.ktb4.team16.mulo.chat.entity.ChatRoom;
import com.ktb4.team16.mulo.chat.entity.ChatRoomParticipant;
import com.ktb4.team16.mulo.chat.exception.ChatApiException;
import com.ktb4.team16.mulo.chat.exception.ChatErrorCode;
import com.ktb4.team16.mulo.chat.repository.ChatRoomParticipantRepository;
import com.ktb4.team16.mulo.chat.repository.ChatRoomRepository;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class ChatRoomCreateServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-13T15:00:00Z"), ZoneOffset.UTC);

    private ChatRoomRepository roomRepository;
    private ChatRoomParticipantRepository participantRepository;
    private ChatRoomCreateTransactionService transactionService;
    private ChatRoomCreateRetryService retryService;
    private ChatRoomCreateService service;

    @BeforeEach
    void setUp() {
        roomRepository = mock(ChatRoomRepository.class);
        participantRepository = mock(ChatRoomParticipantRepository.class);
        transactionService = new ChatRoomCreateTransactionService(roomRepository, participantRepository, CLOCK);
        retryService = new ChatRoomCreateRetryService(transactionService);
        service = new ChatRoomCreateService(transactionService, retryService);
    }

    @Test
    @DisplayName("신규 채팅방과 아직 입장하지 않은 참가자 두 명을 저장한다")
    void createsRoomAndUnenteredParticipants() {
        CreateChatRoomRequest request = request(205_101L, "2026-10-15T00:00:00+09:00", 17L, "jettie", 28L, "lulu");
        ChatRoom room = mock(ChatRoom.class);
        when(room.getChatRoomId()).thenReturn(301L);
        when(roomRepository.findByMatchId(request.matchId())).thenReturn(Optional.empty());
        when(roomRepository.saveAndFlush(org.mockito.ArgumentMatchers.any(ChatRoom.class))).thenReturn(room);

        ChatRoomCreateService.Result result = service.create(request);

        assertThat(result.created()).isTrue();
        assertThat(result.response().chatRoomId()).isEqualTo(301L);
        org.mockito.ArgumentCaptor<Iterable<ChatRoomParticipant>> captor = org.mockito.ArgumentCaptor.captor();
        verify(participantRepository).saveAll(captor.capture());
        List<ChatRoomParticipant> participants = new java.util.ArrayList<>();
        captor.getValue().forEach(participants::add);
        assertThat(participants).hasSize(2)
                .allSatisfy(participant -> {
                    assertThat(participant.getJoinedAt()).isNull();
                    assertThat(participant.getLeftAt()).isNull();
                });
    }

    @Test
    @DisplayName("같은 매칭 정보는 참가자 순서와 관계없이 기존 채팅방을 반환한다")
    void returnsExistingRoomForReorderedParticipants() {
        CreateChatRoomRequest request = request(205_102L, "2026-10-15T00:00:00+09:00", 17L, "jettie", 28L, "lulu");
        ChatRoom room = room(302L, "2026-10-15T00:00:00");
        List<ChatRoomParticipant> participants = List.of(
                participant(room, 28L, "lulu"), participant(room, 17L, "jettie"));
        when(roomRepository.findByMatchId(request.matchId())).thenReturn(Optional.of(room));
        when(participantRepository.findAllByChatRoomChatRoomId(302L)).thenReturn(participants);

        ChatRoomCreateService.Result result = service.create(request);

        assertThat(result.created()).isFalse();
        assertThat(result.response().chatRoomId()).isEqualTo(302L);
        verify(roomRepository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("닉네임은 변경 가능하지만 기존 매칭 참가자나 만료 시각이 다르면 충돌로 처리한다")
    void nicknameChangeDoesNotMakeAnExistingRoomRetryConflict() {
        CreateChatRoomRequest request = request(205_103L, "2026-10-15T00:00:00+09:00", 17L, "jettie", 28L, "lulu");
        ChatRoom room = room(303L, "2026-10-15T00:00:00");
        List<ChatRoomParticipant> participants = List.of(
                participant(room, 17L, "changed"), participant(room, 28L, "lulu"));
        when(roomRepository.findByMatchId(request.matchId())).thenReturn(Optional.of(room));
        when(participantRepository.findAllByChatRoomChatRoomId(303L)).thenReturn(participants);

        assertThat(service.create(request).created()).isFalse();

        CreateChatRoomRequest changedExpiry = request(205_103L, "2026-10-16T00:00:00+09:00", 17L, "jettie", 28L, "lulu");
        List<ChatRoomParticipant> sameParticipants = List.of(
                participant(room, 17L, "changed"), participant(room, 28L, "lulu"));
        when(roomRepository.findByMatchId(changedExpiry.matchId())).thenReturn(Optional.of(room));
        when(participantRepository.findAllByChatRoomChatRoomId(303L)).thenReturn(sameParticipants);
        assertThatThrownBy(() -> service.create(changedExpiry))
                .isInstanceOf(ChatApiException.class)
                .extracting(exception -> ((ChatApiException) exception).getErrorCode())
                .isEqualTo(ChatErrorCode.CHAT_ROOM_CONFLICT);

        List<ChatRoomParticipant> differentParticipants = List.of(
                participant(room, 17L, "changed"), participant(room, 39L, "other"));
        when(participantRepository.findAllByChatRoomChatRoomId(303L)).thenReturn(differentParticipants);
        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(ChatApiException.class)
                .extracting(exception -> ((ChatApiException) exception).getErrorCode())
                .isEqualTo(ChatErrorCode.CHAT_ROOM_CONFLICT);
    }

    @Test
    @DisplayName("신규 만료 요청은 거부하지만 기존 멱등 요청은 반환한다")
    void rejectsExpiredNewRoomAfterCheckingExistingRoomFirst() {
        CreateChatRoomRequest request = request(205_104L, "2026-10-13T23:59:59+09:00", 17L, "jettie", 28L, "lulu");
        when(roomRepository.findByMatchId(request.matchId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(ChatApiException.class)
                .extracting(exception -> ((ChatApiException) exception).getErrorCode())
                .isEqualTo(ChatErrorCode.CHAT_ROOM_CREATE_EXPIRED);

        ChatRoom existing = room(304L, "2026-10-13T23:59:59");
        List<ChatRoomParticipant> participants = List.of(
                participant(existing, 17L, "jettie"), participant(existing, 28L, "lulu"));
        when(roomRepository.findByMatchId(request.matchId())).thenReturn(Optional.of(existing));
        when(participantRepository.findAllByChatRoomChatRoomId(304L)).thenReturn(participants);
        assertThat(service.create(request).created()).isFalse();
    }

    @Test
    @DisplayName("두 참가자에 같은 사용자 ID를 사용할 수 없다")
    void rejectsDuplicateUserIds() {
        CreateChatRoomRequest request = request(205_105L, "2026-10-15T00:00:00+09:00", 17L, "jettie", 17L, "other");

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(ChatApiException.class)
                .extracting(exception -> ((ChatApiException) exception).getErrorCode())
                .isEqualTo(ChatErrorCode.INVALID_INPUT_VALUE);
        verify(roomRepository, never()).findByMatchId(request.matchId());
    }

    @Test
    @DisplayName("matchId 유니크 충돌만 별도 조회로 복구한다")
    void resolvesOnlyMatchIdUniqueConstraintConflict() {
        CreateChatRoomRequest request = request(205_106L, "2026-10-15T00:00:00+09:00", 17L, "jettie", 28L, "lulu");
        ChatRoomCreateTransactionService failingTransaction = mock(ChatRoomCreateTransactionService.class);
        ChatRoomCreateRetryService retry = mock(ChatRoomCreateRetryService.class);
        ChatRoomCreateService retryingService = new ChatRoomCreateService(failingTransaction, retry);
        ChatRoomCreateService.Result existing = new ChatRoomCreateService.Result(new CreateChatRoomResponse(305L), false);
        when(failingTransaction.create(request)).thenThrow(new DataIntegrityViolationException("unique conflict",
                new ConstraintViolationException("duplicate", new SQLException(), "uk_chat_rooms_match")));
        when(retry.resolve(request)).thenReturn(existing);

        assertThat(retryingService.create(request)).isEqualTo(existing);
        verify(retry).resolve(request);

        ChatRoomCreateTransactionService otherFailingTransaction = mock(ChatRoomCreateTransactionService.class);
        when(otherFailingTransaction.create(request)).thenThrow(new DataIntegrityViolationException("other constraint",
                new ConstraintViolationException("duplicate", new SQLException(), "uk_chat_room_participants_room_user")));
        ChatRoomCreateService nonRetryingService = new ChatRoomCreateService(otherFailingTransaction, retry);
        assertThatThrownBy(() -> nonRetryingService.create(request))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(retry, org.mockito.Mockito.times(1)).resolve(request);
    }

    private CreateChatRoomRequest request(
            Long matchId, String expiresAt, Long firstId, String firstName, Long secondId, String secondName
    ) {
        return new CreateChatRoomRequest(matchId, OffsetDateTime.parse(expiresAt), List.of(
                new CreateChatRoomRequest.Participant(firstId, firstName),
                new CreateChatRoomRequest.Participant(secondId, secondName)));
    }

    private ChatRoom room(Long id, String expiresAt) {
        ChatRoom room = mock(ChatRoom.class);
        when(room.getChatRoomId()).thenReturn(id);
        when(room.getExpiresAt()).thenReturn(java.time.LocalDateTime.parse(expiresAt));
        return room;
    }

    private ChatRoomParticipant participant(ChatRoom room, Long userId, String nickname) {
        ChatRoomParticipant participant = mock(ChatRoomParticipant.class);
        when(participant.getUserId()).thenReturn(userId);
        when(participant.getNickname()).thenReturn(nickname);
        return participant;
    }
}
