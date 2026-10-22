package com.ktb4.team16.mulo.chat.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb4.team16.mulo.chat.ChatTime;
import com.ktb4.team16.mulo.chat.entity.ChatMessage;
import com.ktb4.team16.mulo.chat.entity.ChatRoom;
import com.ktb4.team16.mulo.chat.entity.ChatRoomParticipant;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "CHAT_DB_URL", matches = ".+")
class ChatRoomRepositoryTest {

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private ChatRoomParticipantRepository participantRepository;

    @Autowired
    private ChatMessageRepository messageRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void persistsAndReadsExpiresAtAsSeoulLocalTime() {
        OffsetDateTime backendExpiresAt = OffsetDateTime.parse("2026-10-14T00:00:00+09:00");
        ChatRoom chatRoom = ChatRoom.create(205_001L, backendExpiresAt);
        LocalDateTime beforePersist = LocalDateTime.now(ChatTime.KST).minusSeconds(2);

        ChatRoom savedChatRoom = chatRoomRepository.saveAndFlush(chatRoom);
        LocalDateTime afterPersist = LocalDateTime.now(ChatTime.KST).plusSeconds(2);
        entityManager.clear();

        ChatRoom loadedChatRoom = chatRoomRepository.findByMatchId(savedChatRoom.getMatchId()).orElseThrow();

        assertThat(loadedChatRoom.getExpiresAt()).isEqualTo(LocalDateTime.parse("2026-10-14T00:00:00"));
        assertThat(loadedChatRoom.getCreatedAt()).isBetween(beforePersist, afterPersist);
    }

    @Test
    void persistsNewInternalRoomWithTwoUnenteredParticipants() {
        ChatRoom room = chatRoomRepository.saveAndFlush(ChatRoom.create(
                205_003L, OffsetDateTime.parse("2026-10-16T00:00:00+09:00")));
        participantRepository.saveAllAndFlush(List.of(
                ChatRoomParticipant.create(room, 205_003L, "jettie"),
                ChatRoomParticipant.create(room, 205_004L, "lulu")
        ));
        entityManager.clear();

        ChatRoom loadedRoom = chatRoomRepository.findByMatchId(205_003L).orElseThrow();
        List<ChatRoomParticipant> participants =
                participantRepository.findAllByChatRoomChatRoomId(loadedRoom.getChatRoomId());

        assertThat(participants).hasSize(2)
                .allSatisfy(participant -> {
                    assertThat(participant.getJoinedAt()).isNull();
                    assertThat(participant.getLeftAt()).isNull();
                });
        assertThat(loadedRoom.getExpiresAt()).isEqualTo(LocalDateTime.parse("2026-10-16T00:00:00"));
    }

    @Test
    void persistsParticipantAndMessageTimesInKst() {
        ChatRoom room = chatRoomRepository.saveAndFlush(ChatRoom.create(
                205_002L, OffsetDateTime.parse("2026-10-15T00:00:00+09:00")));
        ChatRoomParticipant participant = ChatRoomParticipant.create(room, 205_002L, "jettie");
        LocalDateTime beforePersist = LocalDateTime.now(ChatTime.KST).minusSeconds(2);
        participant.enter();
        participant.leave();
        participantRepository.saveAndFlush(participant);

        ChatMessage message = messageRepository.saveAndFlush(ChatMessage.create(
                room, 205_002L, "550e8400-e29b-41d4-a716-446655440002", "테스트"));
        LocalDateTime afterPersist = LocalDateTime.now(ChatTime.KST).plusSeconds(2);

        assertThat(participant.getJoinedAt()).isBetween(beforePersist, afterPersist);
        assertThat(participant.getLeftAt()).isBetween(beforePersist, afterPersist);
        assertThat(message.getCreatedAt()).isBetween(beforePersist, afterPersist);
    }
}
