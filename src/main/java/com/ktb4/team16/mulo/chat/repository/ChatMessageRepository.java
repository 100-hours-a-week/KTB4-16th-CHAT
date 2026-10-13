package com.ktb4.team16.mulo.chat.repository;

import com.ktb4.team16.mulo.chat.entity.ChatMessage;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    Optional<ChatMessage> findByUserIdAndClientMessageId(Long userId, String clientMessageId);

    boolean existsByUserIdAndClientMessageId(Long userId, String clientMessageId);
}
