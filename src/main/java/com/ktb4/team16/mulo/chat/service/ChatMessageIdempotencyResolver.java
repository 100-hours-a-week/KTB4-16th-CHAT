package com.ktb4.team16.mulo.chat.service;

import com.ktb4.team16.mulo.chat.dto.request.ChatMessageSendRequest;
import com.ktb4.team16.mulo.chat.dto.response.ChatMessageResponse;
import com.ktb4.team16.mulo.chat.entity.ChatMessage;
import com.ktb4.team16.mulo.chat.event.ChatMessageDeliveryEvent;
import com.ktb4.team16.mulo.chat.exception.ChatMessageSendException;
import com.ktb4.team16.mulo.chat.repository.ChatMessageRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatMessageIdempotencyResolver {

    private static final String MESSAGE_TYPE = "MESSAGE";

    private final ChatMessageRepository messageRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ChatMessageIdempotencyResolver(
            ChatMessageRepository messageRepository,
            ApplicationEventPublisher eventPublisher
    ) {
        this.messageRepository = messageRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void resolveUniqueConflict(Long chatRoomId, Long userId, ChatMessageSendRequest request) {
        ChatMessage existing = messageRepository.findByUserIdAndClientMessageId(
                        userId, request.clientMessageId().toString())
                .orElseThrow(() -> ChatMessageSendException.persistenceFailure(request.clientMessageId()));
        if (!existing.getChatRoom().getChatRoomId().equals(chatRoomId)
                || !existing.getContent().equals(request.content())) {
            throw ChatMessageSendException.conflict(request.clientMessageId());
        }
        eventPublisher.publishEvent(new ChatMessageDeliveryEvent(toResponse(existing), List.of(userId)));
    }

    private ChatMessageResponse toResponse(ChatMessage message) {
        return new ChatMessageResponse(
                MESSAGE_TYPE,
                message.getChatRoom().getChatRoomId(),
                message.getChatMessageId(),
                UUID.fromString(message.getClientMessageId()),
                message.getUserId(),
                message.getContent(),
                com.ktb4.team16.mulo.chat.ChatTime.toOffsetDateTime(message.getCreatedAt())
        );
    }
}
