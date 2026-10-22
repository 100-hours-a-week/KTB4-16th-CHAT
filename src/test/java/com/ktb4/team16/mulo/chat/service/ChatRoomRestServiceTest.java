package com.ktb4.team16.mulo.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ktb4.team16.mulo.chat.cursor.ChatMessageCursorCodec;
import com.ktb4.team16.mulo.chat.dto.response.ChatMessageHistoryResponse;
import com.ktb4.team16.mulo.chat.dto.response.ChatParticipantJoinedResponse;
import com.ktb4.team16.mulo.chat.dto.response.ChatRoomDetailResponse;
import com.ktb4.team16.mulo.chat.event.ChatParticipantJoinedDeliveryEvent;
import com.ktb4.team16.mulo.chat.event.ChatParticipantLeftDeliveryEvent;
import com.ktb4.team16.mulo.chat.entity.ChatMessage;
import com.ktb4.team16.mulo.chat.entity.ChatRoom;
import com.ktb4.team16.mulo.chat.entity.ChatRoomParticipant;
import com.ktb4.team16.mulo.chat.exception.ChatApiException;
import com.ktb4.team16.mulo.chat.exception.ChatRoomAccessException;
import com.ktb4.team16.mulo.chat.repository.ChatMessageRepository;
import com.ktb4.team16.mulo.chat.repository.ChatRoomParticipantRepository;
import com.ktb4.team16.mulo.chat.repository.ChatRoomRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
    private ChatMessageCursorCodec cursorCodec;
    private ApplicationEventPublisher eventPublisher;

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-10-14T14:59:59Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        chatRoomRepository = mock(ChatRoomRepository.class);
        participantRepository = mock(ChatRoomParticipantRepository.class);
        messageRepository = mock(ChatMessageRepository.class);
        cursorCodec = new ChatMessageCursorCodec();
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = service(TEST_CLOCK);
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
    @DisplayName("그룹 배정 후 REST 화면 입장에서만 최초 joinedAt을 기록한다")
    void recordsActualGroupScreenEntryWithoutConsumingAnotherSeat() {
        ChatRoom room = activeRoom();
        ChatRoomParticipant reserved = ChatRoomParticipant.createGroup(
                room, USER_ID, "sender", LocalDate.of(2026, 10, 14), "A");
        arrangeRoomAndParticipant(room, reserved);

        service.enter(CHAT_ROOM_ID, USER_ID);
        LocalDateTime firstEntry = reserved.getJoinedAt();
        service.enter(CHAT_ROOM_ID, USER_ID);

        assertThat(firstEntry).isNotNull();
        assertThat(reserved.getJoinedAt()).isEqualTo(firstEntry);
        assertThat(reserved.getLeftAt()).isNull();
    }

    @Test
    @DisplayName("그룹 참가자의 최초 화면 입장만 다른 활성 참가자에게 커밋 후 이벤트를 예약한다")
    void publishesJoinedEventOnlyForFirstGroupScreenEntry() {
        ChatRoom room = activeRoom();
        when(room.getRoomType()).thenReturn(ChatRoom.GROUP_ROOM_TYPE);
        ChatRoomParticipant existing = enteredParticipant(room, 28L, "peer");
        ChatRoomParticipant joining = ChatRoomParticipant.createGroup(
                room, USER_ID, "sender", LocalDate.of(2026, 10, 14), "B");
        arrangeLockedParticipants(room, List.of(existing, joining));

        service.enter(CHAT_ROOM_ID, USER_ID);
        service.enter(CHAT_ROOM_ID, USER_ID);

        org.mockito.ArgumentCaptor<ChatParticipantJoinedDeliveryEvent> eventCaptor =
                org.mockito.ArgumentCaptor.forClass(ChatParticipantJoinedDeliveryEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        ChatParticipantJoinedDeliveryEvent event = eventCaptor.getValue();
        assertThat(event.recipientUserIds()).containsExactly(28L);
        ChatParticipantJoinedResponse response = event.response();
        assertThat(response.type()).isEqualTo("PARTICIPANT_JOINED");
        assertThat(response.chatRoomId()).isEqualTo(CHAT_ROOM_ID);
        assertThat(response.userId()).isEqualTo(USER_ID);
        assertThat(response.nickname()).isEqualTo("sender");
        assertThat(response.voteChoice()).isEqualTo("B");
        assertThat(response.activeParticipantCount()).isEqualTo(2);
        assertThat(response.occurredAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        org.mockito.Mockito.verify(eventPublisher, org.mockito.Mockito.times(1))
                .publishEvent(any(ChatParticipantJoinedDeliveryEvent.class));
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
        ChatRoom room = activeRoom();
        ChatRoomParticipant participant = enteredParticipant(room, USER_ID, "sender");
        ChatRoomParticipant peer = enteredParticipant(room, 28L, "peer");
        arrangeLockedParticipants(room, List.of(participant, peer));

        service.leave(CHAT_ROOM_ID, USER_ID);

        assertThat(participant.getLeftAt()).isNotNull();
        org.mockito.ArgumentCaptor<ChatParticipantLeftDeliveryEvent> eventCaptor =
                org.mockito.ArgumentCaptor.forClass(ChatParticipantLeftDeliveryEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().recipientUserIds()).containsExactly(28L);
        assertThat(eventCaptor.getValue().response().type()).isEqualTo("PARTICIPANT_LEFT");
        assertThat(eventCaptor.getValue().response().chatRoomId()).isEqualTo(CHAT_ROOM_ID);
        assertThat(eventCaptor.getValue().response().userId()).isEqualTo(USER_ID);
        assertThat(eventCaptor.getValue().response().nickname()).isEqualTo("sender");
        assertThat(eventCaptor.getValue().response().leftAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));

        assertCode(HttpStatus.CONFLICT, "CHAT_ALREADY_LEFT", () -> service.leave(CHAT_ROOM_ID, USER_ID));
        verify(eventPublisher, org.mockito.Mockito.times(1)).publishEvent(any(ChatParticipantLeftDeliveryEvent.class));
    }

    @Test
    @DisplayName("마지막 참가자도 퇴장할 수 있고 방을 삭제하거나 이벤트를 발행하지 않는다")
    void allowsLastParticipantToLeaveWithoutDeletingTheRoomOrPublishingAnEvent() {
        ChatRoom room = activeRoom();
        ChatRoomParticipant participant = enteredParticipant(room, USER_ID, "sender");
        arrangeLockedParticipants(room, List.of(participant));

        service.leave(CHAT_ROOM_ID, USER_ID);

        assertThat(participant.getLeftAt()).isNotNull();
        verify(chatRoomRepository, never()).delete(room);
        verify(eventPublisher, never()).publishEvent(any(ChatParticipantLeftDeliveryEvent.class));
    }

    @Test
    @DisplayName("그룹방의 마지막 활성 참가자도 퇴장할 수 있고 누적 정원은 방 상태에 남는다")
    void allowsLastGroupParticipantToLeaveWithoutDeletingTheRoom() {
        ChatRoom room = activeRoom();
        when(room.getRoomType()).thenReturn(ChatRoom.GROUP_ROOM_TYPE);
        when(room.getAdmittedParticipantCount()).thenReturn(4);
        ChatRoomParticipant participant = ChatRoomParticipant.createGroup(
                room, USER_ID, "sender", LocalDate.of(2026, 10, 14), "A");
        participant.enter();
        arrangeLockedParticipants(room, List.of(participant));

        service.leave(CHAT_ROOM_ID, USER_ID);

        assertThat(participant.getLeftAt()).isNotNull();
        assertThat(room.getAdmittedParticipantCount()).isEqualTo(4);
        verify(chatRoomRepository, never()).delete(room);
        verify(eventPublisher, never()).publishEvent(any(ChatParticipantLeftDeliveryEvent.class));
    }

    @Test
    @DisplayName("그룹방 상세 조회에 정원, 만료 시각, 검증된 A/B 선택을 포함한다")
    void groupRoomDetailIncludesCapacityExpiryAndValidatedParticipantChoices() {
        ChatRoom room = activeRoom();
        when(room.getRoomType()).thenReturn("GROUP");
        when(room.getGroupDate()).thenReturn(LocalDate.of(2026, 10, 14));
        when(room.getVoteQuestionId()).thenReturn(91L);
        when(room.getRoomSequence()).thenReturn(2);
        when(room.getCapacity()).thenReturn(30);
        when(room.getAdmittedParticipantCount()).thenReturn(3);
        ChatRoomParticipant requester = ChatRoomParticipant.createGroup(
                room, USER_ID, "sender", LocalDate.of(2026, 10, 14), "A");
        requester.enter();
        ChatRoomParticipant peer = ChatRoomParticipant.createGroup(
                room, 28L, "peer", LocalDate.of(2026, 10, 14), "B");
        peer.enter();
        arrangeRoomAndParticipant(room, requester);
        when(participantRepository.findAllByChatRoomChatRoomId(CHAT_ROOM_ID)).thenReturn(List.of(requester, peer));
        when(participantRepository.countByChatRoomChatRoomIdAndJoinedAtIsNotNullAndLeftAtIsNull(CHAT_ROOM_ID))
                .thenReturn(2L);

        var response = service.getDetail(CHAT_ROOM_ID, USER_ID);

        assertThat(response.roomType()).isEqualTo("GROUP");
        assertThat(response.groupDate()).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(response.voteQuestionId()).isEqualTo(91L);
        assertThat(response.roomSequence()).isEqualTo(2);
        assertThat(response.capacity()).isEqualTo(30);
        assertThat(response.admittedParticipantCount()).isEqualTo(3);
        assertThat(response.activeParticipantCount()).isEqualTo(2);
        assertThat(response.expiresAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(response.participants()).extracting(ChatRoomDetailResponse.Participant::nickname)
                .containsExactly("sender", "peer");
        assertThat(response.participants()).extracting(ChatRoomDetailResponse.Participant::voteChoice)
                .containsExactly("A", "B");
    }

    @Test
    void doesNotPublishLeaveEventWhenTheOtherParticipantHasAlreadyLeft() {
        ChatRoom room = activeRoom();
        ChatRoomParticipant participant = enteredParticipant(room, USER_ID, "sender");
        ChatRoomParticipant peer = enteredParticipant(room, 28L, "peer");
        peer.leave();
        arrangeLockedParticipants(room, List.of(participant, peer));

        service.leave(CHAT_ROOM_ID, USER_ID);

        verify(eventPublisher, never()).publishEvent(any(ChatParticipantLeftDeliveryEvent.class));
    }

    @Test
    void rejectsLeaveForNonParticipantAndExpiredRoomWithoutChangingState() {
        ChatRoom activeRoom = activeRoom();
        when(chatRoomRepository.findById(CHAT_ROOM_ID)).thenReturn(Optional.of(activeRoom));
        when(participantRepository.findByChatRoomChatRoomIdAndUserId(CHAT_ROOM_ID, USER_ID)).thenReturn(Optional.empty());
        assertCode(HttpStatus.NOT_FOUND, "CHAT_ROOM_NOT_FOUND", () -> service.leave(CHAT_ROOM_ID, USER_ID));

        ChatRoom expiredRoom = expiredRoom();
        ChatRoomParticipant participant = enteredParticipant(expiredRoom, USER_ID, "sender");
        ChatRoomParticipant peer = enteredParticipant(expiredRoom, 28L, "peer");
        arrangeLockedParticipants(expiredRoom, List.of(participant, peer));
        assertCode(HttpStatus.FORBIDDEN, "CHAT_ROOM_EXPIRED", () -> service.leave(CHAT_ROOM_ID, USER_ID));
        assertThat(participant.getLeftAt()).isNull();
        verify(eventPublisher, never()).publishEvent(any(ChatParticipantLeftDeliveryEvent.class));
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
        when(user.getNickname()).thenReturn("renamed-jettie");
        arrangeRoomAndParticipant(activeRoom(), user);
        when(messageRepository.findHistory(eq(CHAT_ROOM_ID), any(), any(), any(Pageable.class)))
                .thenReturn(new SliceImpl<>(List.of(newest, oldest), Pageable.ofSize(20), true));
        when(participantRepository.findAllByChatRoomChatRoomId(CHAT_ROOM_ID)).thenReturn(List.of(user));

        ChatMessageHistoryResponse response = service.getHistory(CHAT_ROOM_ID, USER_ID, null);

        assertThat(response.messages()).extracting(ChatMessageHistoryResponse.Message::chatMessageId)
                .containsExactly(301L, 302L);
        assertThat(response.messages().getFirst().createdAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(response.messages().getFirst().createdAt().toLocalDateTime()).isEqualTo(timestamp);
        assertThat(response.messages().getFirst().nickname()).isEqualTo("renamed-jettie");
        assertThat(response.hasNext()).isTrue();
        assertThat(response.nextCursor()).isNotBlank();
    }

    @Test
    void paginatesWithTwentyItemsAndExclusiveTimestampIdCursor() {
        LocalDateTime timestamp = LocalDateTime.of(2026, 10, 13, 15, 30);
        ChatRoomParticipant user = participant(LocalDateTime.now(), null);
        when(user.getUserId()).thenReturn(USER_ID);
        when(user.getNickname()).thenReturn("jettie");
        arrangeRoomAndParticipant(activeRoom(), user);
        when(participantRepository.findAllByChatRoomChatRoomId(CHAT_ROOM_ID)).thenReturn(List.of(user));

        List<ChatMessage> firstPageNewestFirst = descendingMessages(120, 101, timestamp);
        List<ChatMessage> secondPageNewestFirst = descendingMessages(100, 81, timestamp);
        when(messageRepository.findHistory(eq(CHAT_ROOM_ID), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new SliceImpl<>(firstPageNewestFirst, Pageable.ofSize(20), true));
        when(messageRepository.findHistory(eq(CHAT_ROOM_ID), eq(timestamp), eq(101L), any(Pageable.class)))
                .thenReturn(new SliceImpl<>(secondPageNewestFirst, Pageable.ofSize(20), false));

        ChatMessageHistoryResponse first = service.getHistory(CHAT_ROOM_ID, USER_ID, null);
        ChatMessageCursorCodec.Cursor nextCursor = cursorCodec.decode(first.nextCursor());
        ChatMessageHistoryResponse second = service.getHistory(CHAT_ROOM_ID, USER_ID, first.nextCursor());

        assertThat(first.messages()).hasSize(20)
                .extracting(ChatMessageHistoryResponse.Message::chatMessageId)
                .containsExactlyElementsOf(idsAscending(101, 120));
        assertThat(nextCursor).isEqualTo(new ChatMessageCursorCodec.Cursor(timestamp, 101L));
        assertThat(second.messages()).hasSize(20)
                .extracting(ChatMessageHistoryResponse.Message::chatMessageId)
                .containsExactlyElementsOf(idsAscending(81, 100));
        assertThat(first.messages()).extracting(ChatMessageHistoryResponse.Message::chatMessageId)
                .doesNotContainAnyElementsOf(second.messages().stream()
                        .map(ChatMessageHistoryResponse.Message::chatMessageId).toList());
        assertThat(java.util.stream.Stream.concat(
                second.messages().stream().map(ChatMessageHistoryResponse.Message::chatMessageId),
                first.messages().stream().map(ChatMessageHistoryResponse.Message::chatMessageId))
        .toList()).containsExactlyElementsOf(idsAscending(81, 120));
        verify(messageRepository).findHistory(eq(CHAT_ROOM_ID), isNull(), isNull(),
                org.mockito.ArgumentMatchers.argThat(pageable -> pageable.getPageSize() == 20));
        verify(messageRepository).findHistory(eq(CHAT_ROOM_ID), eq(timestamp), eq(101L),
                org.mockito.ArgumentMatchers.argThat(pageable -> pageable.getPageSize() == 20));
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
    void expiresRoomAtKstMidnight() {
        ChatRoom room = mock(ChatRoom.class);
        when(room.getExpiresAt()).thenReturn(LocalDateTime.parse("2026-10-15T00:00:00"));
        ChatRoomParticipant participant = participant(LocalDateTime.parse("2026-10-14T12:00:00"), null);
        arrangeRoomAndParticipant(room, participant);
        when(participantRepository.findAllByChatRoomChatRoomId(CHAT_ROOM_ID)).thenReturn(List.of());

        ChatRoomRestService oneSecondBefore = service(TEST_CLOCK);
        oneSecondBefore.getDetail(CHAT_ROOM_ID, USER_ID);

        ChatRoomRestService atMidnight = service(Clock.fixed(Instant.parse("2026-10-14T15:00:00Z"), ZoneOffset.UTC));
        assertCode(HttpStatus.FORBIDDEN, "CHAT_ROOM_EXPIRED", () -> atMidnight.getDetail(CHAT_ROOM_ID, USER_ID));

        ChatRoomRestService oneSecondAfter = service(Clock.fixed(Instant.parse("2026-10-14T15:00:01Z"), ZoneOffset.UTC));
        assertCode(HttpStatus.FORBIDDEN, "CHAT_ROOM_EXPIRED", () -> oneSecondAfter.getDetail(CHAT_ROOM_ID, USER_ID));
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

    private ChatRoomRestService service(Clock clock) {
        ChatRoomAccessService accessService = new ChatRoomAccessService(chatRoomRepository, participantRepository, clock);
        return new ChatRoomRestService(accessService, participantRepository, messageRepository, cursorCodec, eventPublisher);
    }

    private void arrangeLockedParticipants(ChatRoom room, List<ChatRoomParticipant> participants) {
        when(chatRoomRepository.findById(CHAT_ROOM_ID)).thenReturn(Optional.of(room));
        List<Long> userIds = participants.stream().map(ChatRoomParticipant::getUserId).sorted().toList();
        when(participantRepository.findParticipantUserIdsByChatRoomIdOrderByUserId(CHAT_ROOM_ID))
                .thenReturn(userIds);
        participants.forEach(value -> when(participantRepository
                .findByChatRoomChatRoomIdAndUserIdForUpdate(CHAT_ROOM_ID, value.getUserId()))
                .thenReturn(Optional.of(value)));
    }

    private ChatRoomParticipant enteredParticipant(ChatRoom room, Long userId, String nickname) {
        ChatRoomParticipant participant = ChatRoomParticipant.create(room, userId, nickname);
        participant.enter();
        return participant;
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

    private List<ChatMessage> descendingMessages(int highestId, int lowestId, LocalDateTime timestamp) {
        return java.util.stream.IntStream.iterate(highestId, id -> id >= lowestId, id -> id - 1)
                .mapToObj(id -> message((long) id, timestamp, USER_ID, "message-" + id))
                .toList();
    }

    private List<Long> idsAscending(int lowestId, int highestId) {
        return java.util.stream.LongStream.rangeClosed(lowestId, highestId).boxed().toList();
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
