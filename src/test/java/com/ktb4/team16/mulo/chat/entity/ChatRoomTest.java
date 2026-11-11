package com.ktb4.team16.mulo.chat.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class ChatRoomTest {

    @Test
    void normalizesBackendExpiresAtToSeoulLocalTime() {
        ChatRoom chatRoom = ChatRoom.createGroup(LocalDate.of(2026, 10, 14), 50L, 1, OffsetDateTime.parse("2026-10-13T15:00:00Z"));

        assertThat(chatRoom.getExpiresAt()).isEqualTo(LocalDateTime.parse("2026-10-14T00:00:00"));
    }
}
