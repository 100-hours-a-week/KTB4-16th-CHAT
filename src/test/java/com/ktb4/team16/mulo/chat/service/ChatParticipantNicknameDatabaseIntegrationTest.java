package com.ktb4.team16.mulo.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ktb4.team16.mulo.chat.ChatTime;
import com.ktb4.team16.mulo.chat.dto.response.ChatMessageHistoryResponse;
import com.ktb4.team16.mulo.chat.dto.response.ChatRoomDetailResponse;
import com.ktb4.team16.mulo.chat.entity.ChatMessage;
import com.ktb4.team16.mulo.chat.entity.ChatRoom;
import com.ktb4.team16.mulo.chat.entity.ChatRoomParticipant;
import com.ktb4.team16.mulo.chat.repository.ChatMessageRepository;
import com.ktb4.team16.mulo.chat.repository.ChatRoomParticipantRepository;
import com.ktb4.team16.mulo.chat.repository.ChatRoomRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Verifies nickname persistence, read models and AFTER_COMMIT delivery against the configured MySQL test DB. */
@SpringBootTest(properties = "chat.websocket.allowed-origins=http://localhost")
@EnabledIfEnvironmentVariable(named = "CHAT_DB_URL", matches = ".+")
class ChatParticipantNicknameDatabaseIntegrationTest {

    private static final AtomicLong ID_SEQUENCE = new AtomicLong(System.currentTimeMillis() * 100);
    private static final String ORIGINAL_NICKNAME = "지현";
    private static final String UPDATED_NICKNAME = "뮤로";
    private static final String EVENT_DESTINATION = "/queue/chat-events";

    @Autowired
    private ChatParticipantNicknameService nicknameService;

    @Autowired
    private ChatRoomRestService roomRestService;

    @Autowired
    private ChatRoomRepository roomRepository;

    @Autowired
    private ChatRoomParticipantRepository participantRepository;

    @Autowired
    private ChatMessageRepository messageRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private SimpMessagingTemplate messagingTemplate;

    private final List<Long> createdRoomIds = new ArrayList<>();

    @BeforeEach
    void clearMessagingInvocations() {
        clearInvocations(messagingTemplate);
    }

    @AfterEach
    void removeOnlyFixturesCreatedByThisTest() {
        for (Long roomId : createdRoomIds) {
            jdbcTemplate.update("DELETE FROM chat_messages WHERE chat_room_id = ?", roomId);
            jdbcTemplate.update("DELETE FROM chat_room_participants WHERE chat_room_id = ?", roomId);
            jdbcTemplate.update("DELETE FROM chat_rooms WHERE chat_room_id = ?", roomId);
        }
        createdRoomIds.clear();
    }

    @Test
    @DisplayName("닉네임 변경은 DIRECT/GROUP 참가자와 상세·메시지 이력에 커밋되고 동일 요청은 멱등이다")
    void persistsNicknameAcrossDirectAndGroupRoomsAndReadModels() {
        long userId = nextId();
        ChatRoom directRoom = createDirectRoom();
        addDirectParticipant(directRoom, userId, ORIGINAL_NICKNAME, true, false);
        ChatRoomParticipant directPeer = addDirectParticipant(directRoom, nextId(), "상대1", true, false);
        ChatMessage directMessage = saveMessage(directRoom, userId, "DIRECT 기존 메시지");

        ChatRoom groupRoom = createGroupRoom(userId);
        addGroupParticipant(groupRoom, userId, ORIGINAL_NICKNAME, "A", true, false);
        ChatRoomParticipant groupPeer = addGroupParticipant(groupRoom, nextId(), "상대2", "B", true, false);
        addGroupParticipant(groupRoom, nextId(), "퇴장자", "A", true, true);
        ChatMessage groupMessage = saveMessage(groupRoom, userId, "GROUP 기존 메시지");

        var result = nicknameService.updateNickname(userId, UPDATED_NICKNAME);

        assertThat(result.updatedParticipantCount()).isEqualTo(2);
        assertThat(participantRepository.findByChatRoomChatRoomIdAndUserId(directRoom.getChatRoomId(), userId))
                .get().extracting(ChatRoomParticipant::getNickname).isEqualTo(UPDATED_NICKNAME);
        assertThat(participantRepository.findByChatRoomChatRoomIdAndUserId(groupRoom.getChatRoomId(), userId))
                .get().extracting(ChatRoomParticipant::getNickname).isEqualTo(UPDATED_NICKNAME);
        assertThat(participantRepository.findByChatRoomChatRoomIdAndUserId(directRoom.getChatRoomId(), directPeer.getUserId()))
                .get().extracting(ChatRoomParticipant::getNickname).isEqualTo("상대1");
        assertThat(participantRepository.findByChatRoomChatRoomIdAndUserId(groupRoom.getChatRoomId(), groupPeer.getUserId()))
                .get().extracting(ChatRoomParticipant::getNickname).isEqualTo("상대2");

        assertReadModelsShowUpdatedNickname(directRoom, userId, directMessage);
        assertReadModelsShowUpdatedNickname(groupRoom, userId, groupMessage);

        verify(messagingTemplate, times(2)).convertAndSendToUser(
                eq(userIdString(userId)), eq(EVENT_DESTINATION), any());
        verify(messagingTemplate, times(1)).convertAndSendToUser(
                eq(userIdString(directPeer.getUserId())), eq(EVENT_DESTINATION), any());
        verify(messagingTemplate, times(1)).convertAndSendToUser(
                eq(userIdString(groupPeer.getUserId())), eq(EVENT_DESTINATION), any());
        verify(messagingTemplate, never()).convertAndSendToUser(
                eq(userIdString(findLeftParticipant(groupRoom).getUserId())), eq(EVENT_DESTINATION), any());

        clearInvocations(messagingTemplate);
        var repeated = nicknameService.updateNickname(userId, UPDATED_NICKNAME);

        assertThat(repeated.updatedParticipantCount()).isZero();
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
        assertThat(participantRepository.findByChatRoomChatRoomIdAndUserId(directRoom.getChatRoomId(), userId))
                .get().extracting(ChatRoomParticipant::getNickname).isEqualTo(UPDATED_NICKNAME);
        assertThat(participantRepository.findByChatRoomChatRoomIdAndUserId(groupRoom.getChatRoomId(), userId))
                .get().extracting(ChatRoomParticipant::getNickname).isEqualTo(UPDATED_NICKNAME);
    }

    @Test
    @DisplayName("닉네임 변경 이벤트는 커밋 이후 활성·미만료 방 참가자에게만 전달한다")
    void deliversAfterCommitOnlyToActiveParticipantsInUnexpiredRooms() {
        long userId = nextId();
        ChatRoom activeRoom = createGroupRoom(userId);
        addGroupParticipant(activeRoom, userId, ORIGINAL_NICKNAME, "A", true, false);
        ChatRoomParticipant activePeer = addGroupParticipant(activeRoom, nextId(), "활성상대", "B", true, false);
        ChatRoomParticipant notEnteredPeer = addGroupParticipant(activeRoom, nextId(), "미입장", "A", false, false);
        ChatRoomParticipant leftPeer = addGroupParticipant(activeRoom, nextId(), "퇴장", "B", true, true);

        ChatRoom expiredRoom = createExpiredDirectRoom();
        addDirectParticipant(expiredRoom, userId, ORIGINAL_NICKNAME, true, false);
        ChatRoomParticipant expiredPeer = addDirectParticipant(expiredRoom, nextId(), "만료상대", true, false);

        nicknameService.updateNickname(userId, UPDATED_NICKNAME);

        verify(messagingTemplate).convertAndSendToUser(
                eq(userIdString(userId)), eq(EVENT_DESTINATION), any());
        verify(messagingTemplate).convertAndSendToUser(
                eq(userIdString(activePeer.getUserId())), eq(EVENT_DESTINATION), any());
        verify(messagingTemplate, never()).convertAndSendToUser(
                eq(userIdString(notEnteredPeer.getUserId())), eq(EVENT_DESTINATION), any());
        verify(messagingTemplate, never()).convertAndSendToUser(
                eq(userIdString(leftPeer.getUserId())), eq(EVENT_DESTINATION), any());
        verify(messagingTemplate, never()).convertAndSendToUser(
                eq(userIdString(expiredPeer.getUserId())), eq(EVENT_DESTINATION), any());
    }

    @Test
    @DisplayName("외부 트랜잭션 롤백은 닉네임과 AFTER_COMMIT 이벤트를 모두 취소한다")
    void rollbackDoesNotPersistNicknameOrDeliverEvent() {
        long userId = nextId();
        ChatRoom room = createGroupRoom(userId);
        addGroupParticipant(room, userId, ORIGINAL_NICKNAME, "A", true, false);
        addGroupParticipant(room, nextId(), "상대1", "B", true, false);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            nicknameService.updateNickname(userId, UPDATED_NICKNAME);
            assertThat(messagingTemplate).isNotNull();
            verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
            status.setRollbackOnly();
        });

        assertThat(participantRepository.findByChatRoomChatRoomIdAndUserId(room.getChatRoomId(), userId))
                .get().extracting(ChatRoomParticipant::getNickname).isEqualTo(ORIGINAL_NICKNAME);
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

    @Test
    @DisplayName("커밋 후 메시징 실패가 닉네임 변경 커밋을 되돌리지 않고 다른 수신자 처리를 지속한다")
    void committedNicknameSurvivesOneRecipientDeliveryFailure() {
        long userId = nextId();
        ChatRoom room = createGroupRoom(userId);
        addGroupParticipant(room, userId, ORIGINAL_NICKNAME, "A", true, false);
        ChatRoomParticipant failingPeer = addGroupParticipant(room, nextId(), "실패대상", "B", true, false);
        ChatRoomParticipant healthyPeer = addGroupParticipant(room, nextId(), "정상대상", "A", true, false);
        doThrow(new IllegalStateException("broker unavailable"))
                .when(messagingTemplate)
                .convertAndSendToUser(eq(userIdString(failingPeer.getUserId())), eq(EVENT_DESTINATION), any());

        nicknameService.updateNickname(userId, UPDATED_NICKNAME);

        assertThat(participantRepository.findByChatRoomChatRoomIdAndUserId(room.getChatRoomId(), userId))
                .get().extracting(ChatRoomParticipant::getNickname).isEqualTo(UPDATED_NICKNAME);
        verify(messagingTemplate).convertAndSendToUser(
                eq(userIdString(failingPeer.getUserId())), eq(EVENT_DESTINATION), any());
        verify(messagingTemplate).convertAndSendToUser(
                eq(userIdString(healthyPeer.getUserId())), eq(EVENT_DESTINATION), any());
    }

    private void assertReadModelsShowUpdatedNickname(
            ChatRoom room, long userId, ChatMessage originalMessage
    ) {
        ChatRoomDetailResponse detail = roomRestService.getDetail(room.getChatRoomId(), userId);
        assertThat(detail.participants())
                .filteredOn(participant -> participant.userId().equals(userId))
                .extracting(ChatRoomDetailResponse.Participant::nickname)
                .containsExactly(UPDATED_NICKNAME);

        ChatMessageHistoryResponse history = roomRestService.getHistory(room.getChatRoomId(), userId, null);
        assertThat(history.messages())
                .filteredOn(message -> message.chatMessageId().equals(originalMessage.getChatMessageId()))
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.nickname()).isEqualTo(UPDATED_NICKNAME);
                    assertThat(message.content()).isEqualTo(originalMessage.getContent());
                    assertThat(message.chatMessageId()).isEqualTo(originalMessage.getChatMessageId());
                });
        assertThat(messageRepository.findById(originalMessage.getChatMessageId()))
                .get()
                .satisfies(saved -> {
                    assertThat(saved.getContent()).isEqualTo(originalMessage.getContent());
                    assertThat(saved.getUserId()).isEqualTo(userId);
                });
    }

    private ChatRoom createDirectRoom() {
        ChatRoom room = roomRepository.saveAndFlush(ChatRoom.create(
                nextId(), OffsetDateTime.now(ChatTime.KST).plusDays(1)));
        createdRoomIds.add(room.getChatRoomId());
        return room;
    }

    private ChatRoom createExpiredDirectRoom() {
        ChatRoom room = roomRepository.saveAndFlush(ChatRoom.create(
                nextId(), OffsetDateTime.now(ChatTime.KST).minusDays(1)));
        createdRoomIds.add(room.getChatRoomId());
        return room;
    }

    private ChatRoom createGroupRoom(long userId) {
        ChatRoom room = roomRepository.saveAndFlush(ChatRoom.createGroup(
                LocalDate.now(ChatTime.KST), nextId(), 1, OffsetDateTime.now(ChatTime.KST).plusDays(1)));
        createdRoomIds.add(room.getChatRoomId());
        return room;
    }

    private ChatRoomParticipant addDirectParticipant(
            ChatRoom room, long userId, String nickname, boolean joined, boolean left
    ) {
        ChatRoomParticipant participant = ChatRoomParticipant.create(room, userId, nickname);
        if (joined) {
            participant.enter();
        }
        if (left) {
            participant.leave();
        }
        return participantRepository.saveAndFlush(participant);
    }

    private ChatRoomParticipant addGroupParticipant(
            ChatRoom room, long userId, String nickname, String voteChoice, boolean joined, boolean left
    ) {
        ChatRoomParticipant participant = ChatRoomParticipant.createGroup(
                room, userId, nickname, room.getGroupDate(), voteChoice);
        if (joined) {
            participant.enter();
        }
        if (left) {
            participant.leave();
        }
        return participantRepository.saveAndFlush(participant);
    }

    private ChatMessage saveMessage(ChatRoom room, long userId, String content) {
        return messageRepository.saveAndFlush(ChatMessage.create(room, userId, UUID.randomUUID().toString(), content));
    }

    private ChatRoomParticipant findLeftParticipant(ChatRoom room) {
        return participantRepository.findAllByChatRoomChatRoomId(room.getChatRoomId()).stream()
                .filter(participant -> participant.getLeftAt() != null)
                .findFirst()
                .orElseThrow();
    }

    private long nextId() {
        return ID_SEQUENCE.incrementAndGet();
    }

    private String userIdString(long userId) {
        return Long.toString(userId);
    }
}
