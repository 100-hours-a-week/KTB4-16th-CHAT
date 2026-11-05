package com.ktb4.team16.mulo.chat.repository;

import com.ktb4.team16.mulo.chat.entity.ChatMessage;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    Optional<ChatMessage> findByUserIdAndClientMessageId(Long userId, String clientMessageId);

    boolean existsByUserIdAndClientMessageId(Long userId, String clientMessageId);

    @Query(
            """
            select message
            from ChatMessage message
            where message.chatRoom.chatRoomId = :chatRoomId
              and (:cursorCreatedAt is null
                   or message.createdAt < :cursorCreatedAt
                   or (message.createdAt = :cursorCreatedAt and message.chatMessageId < :cursorMessageId))
            order by message.createdAt desc, message.chatMessageId desc
            """)
    Slice<ChatMessage> findHistory(
            @Param("chatRoomId") Long chatRoomId,
            @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
            @Param("cursorMessageId") Long cursorMessageId,
            Pageable pageable);

    /** 방의 커밋된 메시지에만 복구 상한을 둔다. */
    @Query("select max(m.chatMessageId) from ChatMessage m where m.chatRoom.chatRoomId = :roomId")
    Long findMaxMessageIdByRoomId(@Param("roomId") Long roomId);

    /** 서버 시각과 무관하게 고정 ID 구간을 오름차순 조회한다. */
    @Query(
            "select m from ChatMessage m where m.chatRoom.chatRoomId = :roomId and m.chatMessageId"
                    + " > :after and m.chatMessageId <= :through order by m.chatMessageId")
    java.util.List<ChatMessage> findSyncRange(
            @Param("roomId") Long roomId,
            @Param("after") Long after,
            @Param("through") Long through,
            Pageable pageable);
}
