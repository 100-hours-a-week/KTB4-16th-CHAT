package com.ktb4.team16.mulo.chat.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb4.team16.mulo.chat.entity.ChatRoom;
import com.ktb4.team16.mulo.chat.entity.ChatRoomParticipant;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ChatRoomParticipantRepositoryTest {

    @Autowired
    private ChatRoomParticipantRepository participantRepository;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Test
    void findsOnlyParticipantsWhoJoinedAndHaveNotLeft() {
        ChatRoom chatRoom = chatRoomRepository.saveAndFlush(
                ChatRoom.create(920_003L, OffsetDateTime.parse("2026-10-15T00:00:00+09:00"))
        );
        ChatRoomParticipant active = ChatRoomParticipant.create(chatRoom, 17L, "jettie");
        active.enter();
        ChatRoomParticipant notYetEntered = ChatRoomParticipant.create(chatRoom, 28L, "lulu");
        ChatRoomParticipant alreadyLeft = ChatRoomParticipant.create(chatRoom, 39L, "mulo");
        alreadyLeft.enter();
        alreadyLeft.leave();
        participantRepository.saveAllAndFlush(List.of(active, notYetEntered, alreadyLeft));

        assertThat(participantRepository
                .findAllByChatRoomChatRoomIdAndJoinedAtIsNotNullAndLeftAtIsNull(chatRoom.getChatRoomId()))
                .extracting(ChatRoomParticipant::getUserId)
                .containsExactly(17L);
    }
}
