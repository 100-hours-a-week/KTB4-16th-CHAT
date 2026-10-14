package com.ktb4.team16.mulo.chat.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ktb4.team16.mulo.chat.entity.ChatMessage;
import com.ktb4.team16.mulo.chat.entity.ChatRoom;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ChatMessageRepositoryTest {

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void findsPersistedMessageByUserAndClientMessageId() {
        ChatRoom chatRoom = chatRoomRepository.saveAndFlush(
                ChatRoom.create(920_001L, OffsetDateTime.parse("2026-10-15T00:00:00+09:00"))
        );
        UUID clientMessageId = UUID.randomUUID();
        ChatMessage savedMessage = chatMessageRepository.saveAndFlush(
                ChatMessage.create(chatRoom, 17L, clientMessageId.toString(), "안녕하세요")
        );
        entityManager.clear();

        ChatMessage foundMessage = chatMessageRepository
                .findByUserIdAndClientMessageId(17L, clientMessageId.toString())
                .orElseThrow();

        assertThat(foundMessage.getChatMessageId()).isEqualTo(savedMessage.getChatMessageId());
        assertThat(foundMessage.getChatRoom().getChatRoomId()).isEqualTo(chatRoom.getChatRoomId());
        assertThat(foundMessage.getUserId()).isEqualTo(17L);
        assertThat(foundMessage.getClientMessageId()).isEqualTo(clientMessageId.toString());
        assertThat(foundMessage.getContent()).isEqualTo("안녕하세요");
        assertThat(foundMessage.getCreatedAt()).isNotNull();
    }

    @Test
    void databaseRejectsDuplicateUserAndClientMessageId() {
        ChatRoom chatRoom = chatRoomRepository.saveAndFlush(
                ChatRoom.create(920_002L, OffsetDateTime.parse("2026-10-15T00:00:00+09:00"))
        );
        String clientMessageId = UUID.randomUUID().toString();
        chatMessageRepository.saveAndFlush(
                ChatMessage.create(chatRoom, 17L, clientMessageId, "첫 메시지")
        );

        assertThatThrownBy(() -> chatMessageRepository.saveAndFlush(
                ChatMessage.create(chatRoom, 17L, clientMessageId, "중복 메시지")
        )).isInstanceOf(DataIntegrityViolationException.class);
    }
}
