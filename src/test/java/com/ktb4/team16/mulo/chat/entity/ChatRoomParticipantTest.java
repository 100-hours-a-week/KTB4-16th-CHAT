package com.ktb4.team16.mulo.chat.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ChatRoomParticipantTest {

    @Test
    void enterSetsJoinedAtOnlyOnce() {
        ChatRoomParticipant participant = ChatRoomParticipant.create(ChatRoom.create(1L), 2L, "jettie");

        participant.enter();
        LocalDateTime joinedAt = participant.getJoinedAt();
        participant.enter();

        assertThat(participant.getJoinedAt()).isEqualTo(joinedAt);
    }

    @Test
    void leaveSetsLeftAt() {
        ChatRoomParticipant participant = ChatRoomParticipant.create(ChatRoom.create(1L), 2L, "jettie");

        participant.leave();

        assertThat(participant.getLeftAt()).isNotNull();
    }
}
