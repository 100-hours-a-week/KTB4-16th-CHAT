package com.ktb4.team16.mulo.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ktb4.team16.mulo.chat.dto.request.ChatMessageSendRequest;
import com.ktb4.team16.mulo.chat.entity.ChatMessage;
import com.ktb4.team16.mulo.chat.entity.ChatRoom;
import com.ktb4.team16.mulo.chat.entity.ChatRoomParticipant;
import com.ktb4.team16.mulo.chat.exception.ChatMessageSendException;
import com.ktb4.team16.mulo.chat.exception.ChatSendAccessException;
import com.ktb4.team16.mulo.chat.exception.ChatSendDiagnostic;
import com.ktb4.team16.mulo.chat.exception.ChatRoomAccessException;
import com.ktb4.team16.mulo.chat.repository.ChatMessageRepository;
import com.ktb4.team16.mulo.chat.repository.ChatRoomParticipantRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChatMessageSendPrecheckServiceTest {

    private static final Long ROOM_ID = 23L;
    private static final Long SENDER_ID = 17L;
    private static final Long PEER_ID = 28L;

    private final ChatRoomAccessService accessService = mock(ChatRoomAccessService.class);
    private final ChatRoomParticipantRepository participantRepository = mock(ChatRoomParticipantRepository.class);
    private final ChatMessageRepository messageRepository = mock(ChatMessageRepository.class);
    private final ChatRoom room = mock(ChatRoom.class);
    private final ChatRoomParticipant sender = participant(SENDER_ID, true);
    private final ChatRoomParticipant peer = participant(PEER_ID, true);

    private ChatMessageSendPrecheckService service;

    @BeforeEach
    void setUp() {
        service = new ChatMessageSendPrecheckService(accessService, participantRepository, messageRepository);
        when(accessService.findRoom(ROOM_ID)).thenReturn(room);
        when(participantRepository.findAllByChatRoomChatRoomId(ROOM_ID)).thenReturn(List.of(sender, peer));
        when(accessService.isActiveParticipant(sender)).thenReturn(true);
        when(accessService.isActiveParticipant(peer)).thenReturn(true);
        when(messageRepository.findByUserIdAndClientMessageId(
                SENDER_ID, "00000000-0000-0000-0000-000000000001"))
                .thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("신규 메시지는 최종 검증을 전제로 moderation 필요 상태를 반환한다")
    void requiresModerationForNewMessage() {
        assertThat(service.requiresModeration(ROOM_ID, SENDER_ID, request("본문"))).isTrue();
    }

    @Test
    @DisplayName("동일한 기존 메시지는 필터와 AI 검사를 건너뛸 수 있게 한다")
    void skipsModerationForExactReplay() {
        ChatMessageSendRequest request = request("본문");
        ChatMessage existing = mock(ChatMessage.class);
        when(existing.getChatRoom()).thenReturn(room);
        when(room.getChatRoomId()).thenReturn(ROOM_ID);
        when(existing.getContent()).thenReturn("본문");
        when(messageRepository.findByUserIdAndClientMessageId(
                SENDER_ID, "00000000-0000-0000-0000-000000000001"))
                .thenReturn(Optional.of(existing));

        assertThat(service.requiresModeration(ROOM_ID, SENDER_ID, request)).isFalse();
    }

    @Test
    @DisplayName("같은 멱등 키의 다른 본문은 moderation 전에 충돌로 거부한다")
    void rejectsIdempotencyConflictBeforeModeration() {
        ChatMessage existing = mock(ChatMessage.class);
        when(existing.getChatRoom()).thenReturn(room);
        when(room.getChatRoomId()).thenReturn(ROOM_ID);
        when(existing.getContent()).thenReturn("기존 본문");
        when(messageRepository.findByUserIdAndClientMessageId(
                SENDER_ID, "00000000-0000-0000-0000-000000000001"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.requiresModeration(ROOM_ID, SENDER_ID, request("다른 본문")))
                .isInstanceOf(ChatMessageSendException.class)
                .extracting(exception -> ((ChatMessageSendException) exception).getCode())
                .isEqualTo("CLIENT_MESSAGE_ID_CONFLICT");
    }

    @Test
    @DisplayName("참가자가 정확히 두 명이 아니면 moderation 전에 거부한다")
    void rejectsUnexpectedParticipantCount() {
        when(participantRepository.findAllByChatRoomChatRoomId(ROOM_ID)).thenReturn(List.of(sender));

        assertThatThrownBy(() -> service.requiresModeration(ROOM_ID, SENDER_ID, request("본문")))
                .isInstanceOf(ChatSendAccessException.class)
                .satisfies(exception -> {
                    ChatSendDiagnostic diagnostic = ((ChatSendAccessException) exception).getDiagnostic();
                    assertThat(diagnostic.stage()).isEqualTo(ChatSendDiagnostic.Stage.PRECHECK);
                    assertThat(diagnostic.reason()).isEqualTo(ChatSendDiagnostic.Reason.PARTICIPANT_COUNT_MISMATCH);
                });
    }

    @Test
    @DisplayName("그룹방은 참가자 총원이 둘보다 많아도 활성 참가자가 둘이면 선검사를 통과한다")
    void allowsGroupRoomWhenAtLeastTwoParticipantsAreActive() {
        ChatRoomParticipant third = participant(39L, false);
        when(room.getRoomType()).thenReturn(ChatRoom.GROUP_ROOM_TYPE);
        when(participantRepository.findAllByChatRoomChatRoomId(ROOM_ID)).thenReturn(List.of(sender, peer, third));
        when(accessService.isActiveParticipant(third)).thenReturn(false);

        assertThat(service.requiresModeration(ROOM_ID, SENDER_ID, request("본문"))).isTrue();
    }

    @Test
    @DisplayName("그룹방에서 발신자 외 활성 참가자가 없으면 선검사에서 거부한다")
    void rejectsGroupRoomWhenOnlySenderIsActive() {
        ChatRoomParticipant inactivePeer = participant(PEER_ID, false);
        when(room.getRoomType()).thenReturn(ChatRoom.GROUP_ROOM_TYPE);
        when(participantRepository.findAllByChatRoomChatRoomId(ROOM_ID)).thenReturn(List.of(sender, inactivePeer));
        when(accessService.isActiveParticipant(inactivePeer)).thenReturn(false);

        assertThatThrownBy(() -> service.requiresModeration(ROOM_ID, SENDER_ID, request("본문")))
                .isInstanceOf(ChatSendAccessException.class)
                .satisfies(exception -> {
                    ChatSendDiagnostic diagnostic = ((ChatSendAccessException) exception).getDiagnostic();
                    assertThat(diagnostic.reason()).isEqualTo(ChatSendDiagnostic.Reason.PEER_NOT_ACTIVE);
                });
    }

    @Test
    @DisplayName("DIRECT 상대방이 아직 입장하지 않았으면 별도 접근 사유로 거부한다")
    void rejectsPeerWhoHasNotJoinedYet() {
        when(peer.getJoinedAt()).thenReturn(null);

        assertThatThrownBy(() -> service.requiresModeration(ROOM_ID, SENDER_ID, request("본문")))
                .isInstanceOf(ChatSendAccessException.class)
                .satisfies(exception -> {
                    ChatSendAccessException accessException = (ChatSendAccessException) exception;
                    assertThat(accessException.getReason())
                            .isEqualTo(ChatRoomAccessException.Reason.PEER_NOT_JOINED);
                    assertThat(accessException.getDiagnostic().stage())
                            .isEqualTo(ChatSendDiagnostic.Stage.PRECHECK);
                    assertThat(accessException.getDiagnostic().reason())
                            .isEqualTo(ChatSendDiagnostic.Reason.PEER_NOT_ACTIVE);
                });

        verify(messageRepository, never()).findByUserIdAndClientMessageId(
                SENDER_ID, "00000000-0000-0000-0000-000000000001");
    }

    @Test
    @DisplayName("DIRECT 상대방이 퇴장한 경우 기존 참가자 권한 거부 사유를 유지한다")
    void keepsExistingRejectionWhenPeerHasLeft() {
        when(peer.getLeftAt()).thenReturn(java.time.LocalDateTime.now());
        when(accessService.isActiveParticipant(peer)).thenReturn(false);

        assertThatThrownBy(() -> service.requiresModeration(ROOM_ID, SENDER_ID, request("본문")))
                .isInstanceOf(ChatSendAccessException.class)
                .satisfies(exception -> {
                    ChatSendAccessException accessException = (ChatSendAccessException) exception;
                    assertThat(accessException.getReason())
                            .isEqualTo(ChatRoomAccessException.Reason.PARTICIPANT_LEFT);
                    assertThat(accessException.getDiagnostic().reason())
                            .isEqualTo(ChatSendDiagnostic.Reason.PEER_NOT_ACTIVE);
                });
    }

    private ChatMessageSendRequest request(String content) {
        return new ChatMessageSendRequest(UUID.fromString("00000000-0000-0000-0000-000000000001"), content);
    }

    private static ChatRoomParticipant participant(Long userId, boolean active) {
        ChatRoomParticipant participant = mock(ChatRoomParticipant.class);
        when(participant.getUserId()).thenReturn(userId);
        when(participant.getJoinedAt()).thenReturn(active ? java.time.LocalDateTime.now() : null);
        when(participant.getLeftAt()).thenReturn(null);
        return participant;
    }
}
