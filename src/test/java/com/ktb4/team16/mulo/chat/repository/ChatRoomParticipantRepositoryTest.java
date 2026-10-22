package com.ktb4.team16.mulo.chat.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb4.team16.mulo.chat.entity.ChatRoom;
import com.ktb4.team16.mulo.chat.entity.ChatRoomParticipant;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "CHAT_DB_URL", matches = ".+")
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

    @Test
    @DisplayName("참가자 ID 조회는 사용자 ID 오름차순으로 반환한다")
    void findsRoomParticipantIdsInUserIdOrder() {
        ChatRoom chatRoom = chatRoomRepository.saveAndFlush(
                ChatRoom.create(920_004L, OffsetDateTime.parse("2026-10-15T00:00:00+09:00"))
        );
        ChatRoomParticipant laterUser = ChatRoomParticipant.create(chatRoom, 28L, "lulu");
        laterUser.enter();
        ChatRoomParticipant earlierUser = ChatRoomParticipant.create(chatRoom, 17L, "jettie");
        earlierUser.enter();
        participantRepository.saveAllAndFlush(List.of(laterUser, earlierUser));

        assertThat(participantRepository.findParticipantUserIdsByChatRoomIdOrderByUserId(chatRoom.getChatRoomId()))
                .containsExactly(17L, 28L);
    }
}
