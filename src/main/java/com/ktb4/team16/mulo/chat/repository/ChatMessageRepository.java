package com.ktb4.team16.mulo.chat.repository;

import com.ktb4.team16.mulo.chat.entity.ChatMessage;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    Optional<ChatMessage> findByUserIdAndClientMessageId(Long userId, String clientMessageId);

    boolean existsByUserIdAndClientMessageId(Long userId, String clientMessageId);

    @Query("""
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
            Pageable pageable
    );
}
