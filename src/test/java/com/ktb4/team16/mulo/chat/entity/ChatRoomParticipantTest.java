package com.ktb4.team16.mulo.chat.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb4.team16.mulo.chat.ChatTime;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class ChatRoomParticipantTest {

    @Test
    void enterSetsJoinedAtOnlyOnce() {
        ChatRoomParticipant participant = ChatRoomParticipant.create(
                ChatRoom.create(1L, OffsetDateTime.parse("2026-10-14T00:00:00+09:00")), 2L, "jettie");

        participant.enter();
        LocalDateTime joinedAt = participant.getJoinedAt();
        participant.enter();

        assertThat(participant.getJoinedAt()).isEqualTo(joinedAt);
        assertThat(joinedAt).isBetween(LocalDateTime.now(ChatTime.KST).minusSeconds(2),
                LocalDateTime.now(ChatTime.KST).plusSeconds(2));
    }

    @Test
    void leaveSetsLeftAt() {
        ChatRoomParticipant participant = ChatRoomParticipant.create(
                ChatRoom.create(1L, OffsetDateTime.parse("2026-10-14T00:00:00+09:00")), 2L, "jettie");

        participant.leave();

        assertThat(participant.getLeftAt()).isNotNull();
        assertThat(participant.getLeftAt()).isBetween(LocalDateTime.now(ChatTime.KST).minusSeconds(2),
                LocalDateTime.now(ChatTime.KST).plusSeconds(2));
    }
}
