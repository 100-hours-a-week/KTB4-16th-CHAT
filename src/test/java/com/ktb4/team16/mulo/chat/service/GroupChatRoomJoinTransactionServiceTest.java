package com.ktb4.team16.mulo.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ktb4.team16.mulo.chat.dto.request.GroupChatJoinOrCreateRequest;
import com.ktb4.team16.mulo.chat.entity.ChatRoom;
import com.ktb4.team16.mulo.chat.entity.ChatRoomGroupAllocator;
import com.ktb4.team16.mulo.chat.entity.ChatRoomParticipant;
import com.ktb4.team16.mulo.chat.exception.ChatApiException;
import com.ktb4.team16.mulo.chat.exception.ChatErrorCode;
import com.ktb4.team16.mulo.chat.repository.ChatRoomGroupAllocatorRepository;
import com.ktb4.team16.mulo.chat.repository.ChatRoomParticipantRepository;
import com.ktb4.team16.mulo.chat.repository.ChatRoomRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GroupChatRoomJoinTransactionServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 21);
    private static final Long QUESTION_ID = 88L;
    private static final Long USER_ID = 17L;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"), ZoneOffset.UTC);

    private ChatRoomGroupAllocatorRepository allocatorRepository;
    private ChatRoomRepository roomRepository;
    private ChatRoomParticipantRepository participantRepository;
    private GroupChatRoomJoinTransactionService service;

    @BeforeEach
    void setUp() {
        allocatorRepository = mock(ChatRoomGroupAllocatorRepository.class);
        roomRepository = mock(ChatRoomRepository.class);
        participantRepository = mock(ChatRoomParticipantRepository.class);
        service = new GroupChatRoomJoinTransactionService(
                allocatorRepository, roomRepository, participantRepository, CLOCK);
        when(participantRepository.findByGroupDateAndUserIdForUpdate(TODAY, USER_ID))
                .thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("첫 참가자는 1번 그룹방을 만들고 예약 참가자 한 명을 저장한다")
    void createsFirstGroupRoomAndReservesOneSeat() {
        GroupChatJoinOrCreateRequest request = request(USER_ID, QUESTION_ID, "A");
        ChatRoomGroupAllocator allocator = mock(ChatRoomGroupAllocator.class);
        ChatRoom room = groupRoom(501L, 1, 0);
        AtomicInteger admitted = new AtomicInteger();
        when(room.getAdmittedParticipantCount()).thenAnswer(ignored -> admitted.get());
        doAnswer(ignored -> {
            admitted.incrementAndGet();
            return null;
        }).when(room).admitParticipant();
        when(allocator.getLastRoomSequence()).thenReturn(1);
        when(allocatorRepository.findByGroupDateAndVoteQuestionIdForUpdate(TODAY, QUESTION_ID))
                .thenReturn(Optional.of(allocator));
        when(roomRepository.findFirstByRoomTypeAndGroupDateAndVoteQuestionIdOrderByRoomSequenceDesc(
                "GROUP", TODAY, QUESTION_ID)).thenReturn(Optional.empty());
        when(roomRepository.saveAndFlush(any(ChatRoom.class))).thenReturn(room);
        when(participantRepository.countByChatRoomChatRoomIdAndJoinedAtIsNotNullAndLeftAtIsNull(501L)).thenReturn(0L);

        GroupChatRoomJoinService.Result result = service.joinOrCreate(request);

        assertThat(result.created()).isTrue();
        assertThat(result.response().chatRoomId()).isEqualTo(501L);
        assertThat(result.response().roomSequence()).isEqualTo(1);
        assertThat(result.response().newlyAssigned()).isTrue();
        assertThat(result.response().admittedParticipantCount()).isEqualTo(1);
        assertThat(result.response().activeParticipantCount()).isZero();
        assertThat(result.response().expiresAt().toString()).isEqualTo("2026-10-22T00:00+09:00");

        org.mockito.ArgumentCaptor<ChatRoomParticipant> participant =
                org.mockito.ArgumentCaptor.forClass(ChatRoomParticipant.class);
        verify(participantRepository).saveAndFlush(participant.capture());
        assertThat(participant.getValue().getVoteChoice()).isEqualTo("A");
        assertThat(participant.getValue().getGroupDate()).isEqualTo(TODAY);
        assertThat(participant.getValue().getJoinedAt()).isNull();
        assertThat(participant.getValue().getLeftAt()).isNull();
        verify(allocatorRepository).ensureGroupRow(TODAY, QUESTION_ID);
    }

    @Test
    @DisplayName("실제 입장 인원과 무관하게 누적 29명인 방은 한 자리만 배정한다")
    void reusesRoomByAdmittedCountNotActiveCount() {
        GroupChatJoinOrCreateRequest request = request(USER_ID, QUESTION_ID, "B");
        ChatRoomGroupAllocator allocator = mock(ChatRoomGroupAllocator.class);
        ChatRoom room = groupRoom(502L, 1, 29);
        AtomicInteger admitted = new AtomicInteger(29);
        when(room.getAdmittedParticipantCount()).thenAnswer(ignored -> admitted.get());
        doAnswer(ignored -> {
            admitted.incrementAndGet();
            return null;
        }).when(room).admitParticipant();
        when(allocatorRepository.findByGroupDateAndVoteQuestionIdForUpdate(TODAY, QUESTION_ID))
                .thenReturn(Optional.of(allocator));
        when(roomRepository.findFirstByRoomTypeAndGroupDateAndVoteQuestionIdOrderByRoomSequenceDesc(
                "GROUP", TODAY, QUESTION_ID)).thenReturn(Optional.of(room));
        when(participantRepository.countByChatRoomChatRoomIdAndJoinedAtIsNotNullAndLeftAtIsNull(502L)).thenReturn(1L);

        GroupChatRoomJoinService.Result result = service.joinOrCreate(request);

        assertThat(result.response().admittedParticipantCount()).isEqualTo(30);
        assertThat(result.response().activeParticipantCount()).isEqualTo(1);
        verify(roomRepository, never()).saveAndFlush(any(ChatRoom.class));
        verify(allocator, never()).advanceRoomSequence();
    }

    @Test
    @DisplayName("누적 정원이 찬 마지막 방에는 새 순번의 방을 생성한다")
    void createsNextRoomWhenLastRoomIsFull() {
        GroupChatJoinOrCreateRequest request = request(USER_ID, QUESTION_ID, "A");
        ChatRoomGroupAllocator allocator = mock(ChatRoomGroupAllocator.class);
        ChatRoom fullRoom = groupRoom(503L, 1, 30);
        ChatRoom nextRoom = groupRoom(504L, 2, 1);
        when(allocator.getLastRoomSequence()).thenReturn(2);
        when(allocatorRepository.findByGroupDateAndVoteQuestionIdForUpdate(TODAY, QUESTION_ID))
                .thenReturn(Optional.of(allocator));
        when(roomRepository.findFirstByRoomTypeAndGroupDateAndVoteQuestionIdOrderByRoomSequenceDesc(
                "GROUP", TODAY, QUESTION_ID)).thenReturn(Optional.of(fullRoom));
        when(roomRepository.saveAndFlush(any(ChatRoom.class))).thenReturn(nextRoom);
        when(participantRepository.countByChatRoomChatRoomIdAndJoinedAtIsNotNullAndLeftAtIsNull(504L)).thenReturn(0L);

        GroupChatRoomJoinService.Result result = service.joinOrCreate(request);

        assertThat(result.response().roomSequence()).isEqualTo(2);
        assertThat(result.response().chatRoomId()).isEqualTo(504L);
        verify(allocator).advanceRoomSequence();
        verify(fullRoom, never()).admitParticipant();
    }

    @Test
    @DisplayName("기존 당일 배정 재요청은 같은 방을 반환하고 정원을 다시 쓰지 않는다")
    void returnsExistingAssignmentIdempotently() {
        GroupChatJoinOrCreateRequest request = request(USER_ID, QUESTION_ID, "A");
        ChatRoom room = groupRoom(505L, 1, 30);
        ChatRoomParticipant participant = mock(ChatRoomParticipant.class);
        when(participant.getChatRoom()).thenReturn(room);
        when(participant.getVoteChoice()).thenReturn("A");
        when(participant.getLeftAt()).thenReturn(null);
        when(participantRepository.findByGroupDateAndUserIdForUpdate(TODAY, USER_ID))
                .thenReturn(Optional.of(participant));
        when(participantRepository.countByChatRoomChatRoomIdAndJoinedAtIsNotNullAndLeftAtIsNull(505L)).thenReturn(3L);

        GroupChatRoomJoinService.Result result = service.joinOrCreate(request);

        assertThat(result.created()).isFalse();
        assertThat(result.response().newlyAssigned()).isFalse();
        assertThat(result.response().chatRoomId()).isEqualTo(505L);
        verify(participantRepository, never()).saveAndFlush(any(ChatRoomParticipant.class));
        verify(roomRepository, never()).saveAndFlush(any(ChatRoom.class));
    }

    @Test
    @DisplayName("그룹 할당 잠금을 기다리는 동안 선행 요청이 커밋한 배정을 재사용한다")
    void rechecksAssignmentAfterWaitingForAllocatorLock() {
        GroupChatJoinOrCreateRequest request = request(USER_ID, QUESTION_ID, "A");
        ChatRoomParticipant participant = mock(ChatRoomParticipant.class);
        ChatRoom room = groupRoom(508L, 1, 1);
        when(participant.getChatRoom()).thenReturn(room);
        when(participant.getVoteChoice()).thenReturn("A");
        when(participant.getLeftAt()).thenReturn(null);
        when(participantRepository.findByGroupDateAndUserIdForUpdate(TODAY, USER_ID))
                .thenReturn(Optional.empty(), Optional.of(participant));
        when(allocatorRepository.findByGroupDateAndVoteQuestionIdForUpdate(TODAY, QUESTION_ID))
                .thenReturn(Optional.of(mock(ChatRoomGroupAllocator.class)));
        when(participantRepository.countByChatRoomChatRoomIdAndJoinedAtIsNotNullAndLeftAtIsNull(508L))
                .thenReturn(0L);

        GroupChatRoomJoinService.Result result = service.joinOrCreate(request);

        assertThat(result.created()).isFalse();
        assertThat(result.response().chatRoomId()).isEqualTo(508L);
        verify(participantRepository, org.mockito.Mockito.times(2))
                .findByGroupDateAndUserIdForUpdate(TODAY, USER_ID);
        verify(participantRepository, never()).saveAndFlush(any(ChatRoomParticipant.class));
        verify(roomRepository, never()).saveAndFlush(any(ChatRoom.class));
    }

    @Test
    @DisplayName("퇴장한 사용자는 같은 날 그룹 배정 재요청으로 재입장할 수 없다")
    void rejectsFormerParticipantRetry() {
        GroupChatJoinOrCreateRequest request = request(USER_ID, QUESTION_ID, "A");
        ChatRoomParticipant participant = mock(ChatRoomParticipant.class);
        ChatRoom room = groupRoom(506L, 1, 30);
        when(participant.getChatRoom()).thenReturn(room);
        when(participant.getVoteChoice()).thenReturn("A");
        when(participant.getLeftAt()).thenReturn(LocalDateTime.now());
        when(participantRepository.findByGroupDateAndUserIdForUpdate(TODAY, USER_ID))
                .thenReturn(Optional.of(participant));

        assertThatThrownBy(() -> service.joinOrCreate(request))
                .isInstanceOf(ChatApiException.class)
                .extracting(exception -> ((ChatApiException) exception).getErrorCode())
                .isEqualTo(ChatErrorCode.CHAT_REENTRY_FORBIDDEN);
    }

    @Test
    @DisplayName("오늘이 아닌 투표 날짜의 그룹 입장은 거부한다")
    void rejectsNonCurrentGroupDate() {
        GroupChatJoinOrCreateRequest request = new GroupChatJoinOrCreateRequest(
                TODAY.minusDays(1), QUESTION_ID, USER_ID, "nickname", "A");

        assertThatThrownBy(() -> service.joinOrCreate(request))
                .isInstanceOf(ChatApiException.class)
                .extracting(exception -> ((ChatApiException) exception).getErrorCode())
                .isEqualTo(ChatErrorCode.INVALID_INPUT_VALUE);
        verify(allocatorRepository, never()).ensureGroupRow(any(), any());
    }

    @Test
    @DisplayName("기존 사용자 배정의 질문 또는 선택이 달라지면 충돌로 처리한다")
    void rejectsChangedAssignment() {
        ChatRoomParticipant participant = mock(ChatRoomParticipant.class);
        ChatRoom room = groupRoom(507L, 1, 1);
        when(participant.getChatRoom()).thenReturn(room);
        when(participant.getVoteChoice()).thenReturn("A");
        when(participantRepository.findByGroupDateAndUserIdForUpdate(TODAY, USER_ID))
                .thenReturn(Optional.of(participant));

        assertThatThrownBy(() -> service.joinOrCreate(request(USER_ID, QUESTION_ID, "B")))
                .isInstanceOf(ChatApiException.class)
                .extracting(exception -> ((ChatApiException) exception).getErrorCode())
                .isEqualTo(ChatErrorCode.CHAT_GROUP_ASSIGNMENT_CONFLICT);

        assertThatThrownBy(() -> service.joinOrCreate(request(USER_ID, QUESTION_ID + 1, "A")))
                .isInstanceOf(ChatApiException.class)
                .extracting(exception -> ((ChatApiException) exception).getErrorCode())
                .isEqualTo(ChatErrorCode.CHAT_GROUP_ASSIGNMENT_CONFLICT);
    }

    private GroupChatJoinOrCreateRequest request(Long userId, Long questionId, String choice) {
        return new GroupChatJoinOrCreateRequest(TODAY, questionId, userId, "nickname", choice);
    }

    private ChatRoom groupRoom(Long id, int sequence, int admittedCount) {
        ChatRoom room = mock(ChatRoom.class);
        when(room.getChatRoomId()).thenReturn(id);
        when(room.getRoomType()).thenReturn("GROUP");
        when(room.getVoteQuestionId()).thenReturn(QUESTION_ID);
        when(room.getRoomSequence()).thenReturn(sequence);
        when(room.getCapacity()).thenReturn(30);
        when(room.getAdmittedParticipantCount()).thenReturn(admittedCount);
        when(room.getExpiresAt()).thenReturn(TODAY.plusDays(1).atStartOfDay());
        return room;
    }
}
