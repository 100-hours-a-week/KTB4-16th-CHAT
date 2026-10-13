package com.ktb4.team16.mulo.chat.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb4.team16.mulo.chat.entity.ChatRoom;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ChatRoomRepositoryTest {

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void persistsAndReadsExpiresAtAsSeoulLocalTime() {
        OffsetDateTime backendExpiresAt = OffsetDateTime.parse("2026-10-14T00:00:00+09:00");
        ChatRoom chatRoom = ChatRoom.create(205_001L, backendExpiresAt);

        ChatRoom savedChatRoom = chatRoomRepository.saveAndFlush(chatRoom);
        entityManager.clear();

        ChatRoom loadedChatRoom = chatRoomRepository.findByMatchId(savedChatRoom.getMatchId()).orElseThrow();

        assertThat(loadedChatRoom.getExpiresAt()).isEqualTo(LocalDateTime.parse("2026-10-14T00:00:00"));
    }
}
