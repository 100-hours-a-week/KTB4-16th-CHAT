package com.ktb4.team16.mulo.chat.repository;

import com.ktb4.team16.mulo.chat.entity.ChatRoomParticipant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRoomParticipantRepository extends JpaRepository<ChatRoomParticipant, Long> {

    Optional<ChatRoomParticipant> findByChatRoomChatRoomIdAndUserId(Long chatRoomId, Long userId);

    List<ChatRoomParticipant> findAllByChatRoomChatRoomId(Long chatRoomId);

    boolean existsByChatRoomChatRoomIdAndUserId(Long chatRoomId, Long userId);

    long countByChatRoomChatRoomId(Long chatRoomId);
}
