package com.ktb4.team16.mulo.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ktb4.team16.mulo.chat.dto.request.ChatMessageSendRequest;
import com.ktb4.team16.mulo.chat.entity.ChatMessage;
import com.ktb4.team16.mulo.chat.entity.ChatRoom;
import com.ktb4.team16.mulo.chat.entity.ChatRoomParticipant;
import com.ktb4.team16.mulo.chat.event.ChatMessageDeliveryEvent;
import com.ktb4.team16.mulo.chat.exception.ChatMessageSendException;
import com.ktb4.team16.mulo.chat.exception.ChatRoomAccessException;
import com.ktb4.team16.mulo.chat.repository.ChatMessageRepository;
import com.ktb4.team16.mulo.chat.repository.ChatRoomParticipantRepository;
import com.ktb4.team16.mulo.chat.repository.ChatRoomRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

class ChatMessageSendServiceTest {

    private static final Long CHAT_ROOM_ID = 23L;
    private static final Long SENDER_ID = 17L;
    private static final Long PEER_ID = 28L;
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-10-14T14:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime JOINED_AT = LocalDateTime.parse("2026-10-14T10:00:00");

    private final ChatRoomRepository roomRepository = mock(ChatRoomRepository.class);
    private final ChatRoomParticipantRepository participantRepository = mock(ChatRoomParticipantRepository.class);
    private final ChatMessageRepository messageRepository = mock(ChatMessageRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final ChatRoom room = mock(ChatRoom.class);
    private final ChatRoomParticipant sender = participant(SENDER_ID, JOINED_AT, null);
    private final ChatRoomParticipant peer = participant(PEER_ID, JOINED_AT, null);
    private final ChatMessage savedMessage = mock(ChatMessage.class);

    private ChatMessageSendTransactionService transactionService;
    private ChatMessageSendService sendService;

    @BeforeEach
    void setUp() {
        ChatRoomAccessService accessService = new ChatRoomAccessService(
                roomRepository, participantRepository, TEST_CLOCK);
        transactionService = new ChatMessageSendTransactionService(accessService, messageRepository, eventPublisher);
        sendService = new ChatMessageSendService(transactionService, mock(ChatMessageIdempotencyResolver.class));

        when(roomRepository.findById(CHAT_ROOM_ID)).thenReturn(Optional.of(room));
        when(room.getChatRoomId()).thenReturn(CHAT_ROOM_ID);
        when(room.getExpiresAt()).thenReturn(LocalDateTime.parse("2026-10-15T00:00:00"));
        when(participantRepository.findParticipantUserIdsByChatRoomIdOrderByUserId(CHAT_ROOM_ID))
                .thenReturn(List.of(SENDER_ID, PEER_ID));
        when(participantRepository.findByChatRoomChatRoomIdAndUserIdForUpdate(CHAT_ROOM_ID, SENDER_ID))
                .thenReturn(Optional.of(sender));
        when(participantRepository.findByChatRoomChatRoomIdAndUserIdForUpdate(CHAT_ROOM_ID, PEER_ID))
                .thenReturn(Optional.of(peer));
    }

    @Test
    @DisplayName("최초 메시지를 저장하고 양쪽 사용자에게 전달 이벤트를 만든다")
    void storesNewMessageAndSchedulesDeliveryForBothUsers() {
        UUID clientMessageId = UUID.randomUUID();
        ChatMessageSendRequest request = new ChatMessageSendRequest(clientMessageId, " 안녕하세요 ");
        when(messageRepository.findByUserIdAndClientMessageId(SENDER_ID, clientMessageId.toString()))
                .thenReturn(Optional.empty());
        when(messageRepository.saveAndFlush(any(ChatMessage.class))).thenReturn(savedMessage);
        when(savedMessage.getChatMessageId()).thenReturn(101L);
        when(savedMessage.getChatRoom()).thenReturn(room);
        when(savedMessage.getUserId()).thenReturn(SENDER_ID);
        when(savedMessage.getClientMessageId()).thenReturn(clientMessageId.toString());
        when(savedMessage.getContent()).thenReturn(request.content());
        when(savedMessage.getCreatedAt()).thenReturn(LocalDateTime.parse("2026-10-14T23:00:00"));

        transactionService.send(CHAT_ROOM_ID, SENDER_ID, request);

        ArgumentCaptor<ChatMessage> messageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageRepository).saveAndFlush(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getContent()).isEqualTo(" 안녕하세요 ");
        ArgumentCaptor<ChatMessageDeliveryEvent> eventCaptor = ArgumentCaptor.forClass(ChatMessageDeliveryEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().recipientUserIds()).containsExactly(SENDER_ID, PEER_ID);
        assertThat(eventCaptor.getValue().response().type()).isEqualTo("MESSAGE");
        assertThat(eventCaptor.getValue().response().createdAt().getOffset().getId()).isEqualTo("+09:00");

        InOrder lockOrder = inOrder(participantRepository);
        lockOrder.verify(participantRepository).findByChatRoomChatRoomIdAndUserIdForUpdate(CHAT_ROOM_ID, SENDER_ID);
        lockOrder.verify(participantRepository).findByChatRoomChatRoomIdAndUserIdForUpdate(CHAT_ROOM_ID, PEER_ID);
    }

    @Test
    @DisplayName("동일한 멱등 재전송은 저장 없이 발신자에게만 기존 결과를 전달한다")
    void reusesIdenticalMessageForSenderOnly() {
        UUID clientMessageId = UUID.randomUUID();
        ChatMessage existing = message(clientMessageId, "같은 내용");
        when(messageRepository.findByUserIdAndClientMessageId(SENDER_ID, clientMessageId.toString()))
                .thenReturn(Optional.of(existing));

        transactionService.send(CHAT_ROOM_ID, SENDER_ID, new ChatMessageSendRequest(clientMessageId, "같은 내용"));

        verify(messageRepository, never()).saveAndFlush(any(ChatMessage.class));
        ArgumentCaptor<ChatMessageDeliveryEvent> eventCaptor = ArgumentCaptor.forClass(ChatMessageDeliveryEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().recipientUserIds()).containsExactly(SENDER_ID);
    }

    @Test
    @DisplayName("같은 멱등 키에 다른 채팅방 또는 본문을 사용하면 저장과 전달을 거부한다")
    void rejectsConflictingIdempotencyKey() {
        UUID clientMessageId = UUID.randomUUID();
        ChatMessage existing = message(clientMessageId, "기존 내용");
        when(messageRepository.findByUserIdAndClientMessageId(SENDER_ID, clientMessageId.toString()))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> transactionService.send(
                CHAT_ROOM_ID, SENDER_ID, new ChatMessageSendRequest(clientMessageId, "다른 내용")))
                .isInstanceOf(ChatMessageSendException.class)
                .extracting(exception -> ((ChatMessageSendException) exception).getCode())
                .isEqualTo("CLIENT_MESSAGE_ID_CONFLICT");

        verify(messageRepository, never()).saveAndFlush(any(ChatMessage.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("같은 멱등 키의 기존 메시지가 다른 채팅방이면 충돌로 거부한다")
    void rejectsIdempotencyKeyFromAnotherRoom() {
        UUID clientMessageId = UUID.randomUUID();
        ChatRoom otherRoom = mock(ChatRoom.class);
        when(otherRoom.getChatRoomId()).thenReturn(24L);
        ChatMessage existing = message(clientMessageId, "같은 내용");
        when(existing.getChatRoom()).thenReturn(otherRoom);
        when(messageRepository.findByUserIdAndClientMessageId(SENDER_ID, clientMessageId.toString()))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> transactionService.send(
                CHAT_ROOM_ID, SENDER_ID, new ChatMessageSendRequest(clientMessageId, "같은 내용")))
                .isInstanceOf(ChatMessageSendException.class)
                .extracting(exception -> ((ChatMessageSendException) exception).getCode())
                .isEqualTo("CLIENT_MESSAGE_ID_CONFLICT");

        verify(messageRepository, never()).saveAndFlush(any(ChatMessage.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("동시 저장의 UNIQUE 충돌은 롤백 뒤 멱등성 재조회로 넘긴다")
    void resolvesUniqueConstraintRaceOutsideTheSaveTransaction() {
        ChatMessageSendTransactionService saveTransaction = mock(ChatMessageSendTransactionService.class);
        ChatMessageIdempotencyResolver resolver = mock(ChatMessageIdempotencyResolver.class);
        ChatMessageSendService service = new ChatMessageSendService(saveTransaction, resolver);
        UUID clientMessageId = UUID.randomUUID();
        ChatMessageSendRequest request = new ChatMessageSendRequest(clientMessageId, "안녕하세요");
        doThrow(new DataIntegrityViolationException("unique constraint"))
                .when(saveTransaction).send(eq(CHAT_ROOM_ID), eq(SENDER_ID), eq(request));

        service.send(CHAT_ROOM_ID, SENDER_ID, request);

        verify(resolver).resolveUniqueConflict(CHAT_ROOM_ID, SENDER_ID, request);
    }

    @Test
    @DisplayName("상대 참가자가 입장하지 않았거나 퇴장했으면 저장하지 않는다")
    void rejectsWhenPeerIsNotActive() {
        when(peer.getJoinedAt()).thenReturn(null);
        UUID clientMessageId = UUID.randomUUID();

        assertThatThrownBy(() -> sendService.send(
                CHAT_ROOM_ID, SENDER_ID, new ChatMessageSendRequest(clientMessageId, "안녕하세요")))
                .isInstanceOf(ChatMessageSendException.class)
                .extracting(exception -> ((ChatMessageSendException) exception).getCode())
                .isEqualTo("CHAT_SEND_FORBIDDEN");

        verify(messageRepository, never()).saveAndFlush(any(ChatMessage.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("입장하지 않았거나 퇴장한 발신자는 메시지를 보낼 수 없다")
    void rejectsInactiveSender() {
        UUID clientMessageId = UUID.randomUUID();
        when(sender.getJoinedAt()).thenReturn(null);

        assertThatThrownBy(() -> sendService.send(
                CHAT_ROOM_ID, SENDER_ID, new ChatMessageSendRequest(clientMessageId, "안녕하세요")))
                .isInstanceOf(ChatMessageSendException.class)
                .extracting(exception -> ((ChatMessageSendException) exception).getCode())
                .isEqualTo("CHAT_SEND_FORBIDDEN");

        verify(messageRepository, never()).saveAndFlush(any(ChatMessage.class));
    }

    @Test
    @DisplayName("퇴장한 발신자는 메시지를 보낼 수 없다")
    void rejectsSenderWhoHasLeft() {
        UUID clientMessageId = UUID.randomUUID();
        when(sender.getLeftAt()).thenReturn(LocalDateTime.parse("2026-10-14T12:00:00"));

        assertThatThrownBy(() -> sendService.send(
                CHAT_ROOM_ID, SENDER_ID, new ChatMessageSendRequest(clientMessageId, "안녕하세요")))
                .isInstanceOf(ChatMessageSendException.class)
                .extracting(exception -> ((ChatMessageSendException) exception).getCode())
                .isEqualTo("CHAT_SEND_FORBIDDEN");

        verify(messageRepository, never()).saveAndFlush(any(ChatMessage.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("퇴장한 상대 참가자가 있으면 메시지를 보낼 수 없다")
    void rejectsWhenPeerHasLeft() {
        UUID clientMessageId = UUID.randomUUID();
        when(peer.getLeftAt()).thenReturn(LocalDateTime.parse("2026-10-14T12:00:00"));

        assertThatThrownBy(() -> sendService.send(
                CHAT_ROOM_ID, SENDER_ID, new ChatMessageSendRequest(clientMessageId, "안녕하세요")))
                .isInstanceOf(ChatMessageSendException.class)
                .extracting(exception -> ((ChatMessageSendException) exception).getCode())
                .isEqualTo("CHAT_SEND_FORBIDDEN");

        verify(messageRepository, never()).saveAndFlush(any(ChatMessage.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("만료된 채팅방의 메시지 전송은 만료 오류로 거부한다")
    void rejectsExpiredRoom() {
        UUID clientMessageId = UUID.randomUUID();
        when(room.getExpiresAt()).thenReturn(LocalDateTime.parse("2026-10-14T22:59:59"));

        assertThatThrownBy(() -> sendService.send(
                CHAT_ROOM_ID, SENDER_ID, new ChatMessageSendRequest(clientMessageId, "안녕하세요")))
                .isInstanceOf(ChatMessageSendException.class)
                .extracting(exception -> ((ChatMessageSendException) exception).getCode())
                .isEqualTo("CHAT_ROOM_EXPIRED");

        verify(messageRepository, never()).saveAndFlush(any(ChatMessage.class));
    }

    @Test
    @DisplayName("양쪽 참가자가 정확히 두 명이 아니면 전송을 거부한다")
    void rejectsRoomWithUnexpectedParticipantCount() {
        when(participantRepository.findParticipantUserIdsByChatRoomIdOrderByUserId(CHAT_ROOM_ID))
                .thenReturn(List.of(SENDER_ID, PEER_ID, 39L));

        assertThatThrownBy(() -> sendService.send(
                CHAT_ROOM_ID, SENDER_ID, new ChatMessageSendRequest(UUID.randomUUID(), "안녕하세요")))
                .isInstanceOf(ChatMessageSendException.class)
                .extracting(exception -> ((ChatMessageSendException) exception).getCode())
                .isEqualTo("CHAT_SEND_FORBIDDEN");

        verify(messageRepository, never()).saveAndFlush(any(ChatMessage.class));
    }

    @Test
    @DisplayName("채팅방이 없으면 기존 접근 오류를 전송 오류 계약으로 변환한다")
    void mapsMissingRoomToSendForbidden() {
        when(roomRepository.findById(CHAT_ROOM_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sendService.send(
                CHAT_ROOM_ID, SENDER_ID, new ChatMessageSendRequest(UUID.randomUUID(), "안녕하세요")))
                .isInstanceOf(ChatMessageSendException.class)
                .extracting(exception -> ((ChatMessageSendException) exception).getCode())
                .isEqualTo("CHAT_SEND_FORBIDDEN");
    }

    @Test
    @DisplayName("양쪽 참가자를 userId 오름차순으로 잠근다")
    void locksParticipantsInAscendingUserIdOrder() {
        UUID clientMessageId = UUID.randomUUID();
        ChatMessage existing = message(clientMessageId, "기존 내용");
        when(messageRepository.findByUserIdAndClientMessageId(SENDER_ID, clientMessageId.toString()))
                .thenReturn(Optional.of(existing));

        transactionService.send(CHAT_ROOM_ID, SENDER_ID, new ChatMessageSendRequest(clientMessageId, "기존 내용"));

        InOrder lockOrder = inOrder(participantRepository);
        lockOrder.verify(participantRepository).findByChatRoomChatRoomIdAndUserIdForUpdate(CHAT_ROOM_ID, SENDER_ID);
        lockOrder.verify(participantRepository).findByChatRoomChatRoomIdAndUserIdForUpdate(CHAT_ROOM_ID, PEER_ID);
    }

    private ChatMessage message(UUID clientMessageId, String content) {
        ChatMessage existing = mock(ChatMessage.class);
        when(existing.getChatRoom()).thenReturn(room);
        when(existing.getUserId()).thenReturn(SENDER_ID);
        when(existing.getClientMessageId()).thenReturn(clientMessageId.toString());
        when(existing.getContent()).thenReturn(content);
        when(existing.getChatMessageId()).thenReturn(101L);
        when(existing.getCreatedAt()).thenReturn(LocalDateTime.parse("2026-10-14T23:00:00"));
        return existing;
    }

    private static ChatRoomParticipant participant(Long userId, LocalDateTime joinedAt, LocalDateTime leftAt) {
        ChatRoomParticipant participant = mock(ChatRoomParticipant.class);
        when(participant.getUserId()).thenReturn(userId);
        when(participant.getJoinedAt()).thenReturn(joinedAt);
        when(participant.getLeftAt()).thenReturn(leftAt);
        return participant;
    }
}
