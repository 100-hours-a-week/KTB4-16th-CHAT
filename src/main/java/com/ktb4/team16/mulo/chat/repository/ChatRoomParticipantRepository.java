package com.ktb4.team16.mulo.chat.repository;

import com.ktb4.team16.mulo.chat.entity.ChatRoomParticipant;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.time.LocalDate;
import java.util.Collection;
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

    @Query("""
            select participant.userId
            from ChatRoomParticipant participant
            where participant.chatRoom.chatRoomId = :chatRoomId
            order by participant.userId
            """)
    List<Long> findParticipantUserIdsByChatRoomIdOrderByUserId(@Param("chatRoomId") Long chatRoomId);

    List<ChatRoomParticipant> findAllByChatRoomChatRoomId(Long chatRoomId);

    List<ChatRoomParticipant> findAllByChatRoomChatRoomIdAndJoinedAtIsNotNullAndLeftAtIsNull(Long chatRoomId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select participant
            from ChatRoomParticipant participant
            where participant.userId = :userId
            order by participant.chatRoomParticipantId
            """)
    List<ChatRoomParticipant> findAllByUserIdForUpdate(@Param("userId") Long userId);

    List<ChatRoomParticipant> findAllByChatRoomChatRoomIdInAndJoinedAtIsNotNullAndLeftAtIsNull(
            Collection<Long> chatRoomIds
    );

    boolean existsByChatRoomChatRoomIdAndUserId(Long chatRoomId, Long userId);

    long countByChatRoomChatRoomId(Long chatRoomId);

    Optional<ChatRoomParticipant> findByGroupDateAndUserId(LocalDate groupDate, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select participant
            from ChatRoomParticipant participant
            where participant.groupDate = :groupDate
              and participant.userId = :userId
            """)
    Optional<ChatRoomParticipant> findByGroupDateAndUserIdForUpdate(
            @Param("groupDate") LocalDate groupDate, @Param("userId") Long userId
    );

    long countByChatRoomChatRoomIdAndJoinedAtIsNotNullAndLeftAtIsNull(Long chatRoomId);
}
