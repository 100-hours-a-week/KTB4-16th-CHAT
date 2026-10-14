package com.ktb4.team16.mulo.chat.repository;

import com.ktb4.team16.mulo.chat.entity.ChatRoomParticipant;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatRoomParticipantRepository extends JpaRepository<ChatRoomParticipant, Long> {

    Optional<ChatRoomParticipant> findByChatRoomChatRoomIdAndUserId(Long chatRoomId, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select participant
            from ChatRoomParticipant participant
            where participant.chatRoom.chatRoomId = :chatRoomId
              and participant.userId = :userId
            """)
    Optional<ChatRoomParticipant> findByChatRoomChatRoomIdAndUserIdForUpdate(
            @Param("chatRoomId") Long chatRoomId,
            @Param("userId") Long userId
    );

    List<ChatRoomParticipant> findAllByChatRoomChatRoomId(Long chatRoomId);

    boolean existsByChatRoomChatRoomIdAndUserId(Long chatRoomId, Long userId);

    long countByChatRoomChatRoomId(Long chatRoomId);
}
