package com.ktb4.team16.mulo.chat.service;

import com.ktb4.team16.mulo.chat.ChatTime;
import com.ktb4.team16.mulo.chat.dto.request.ChatMessageSendRequest;
import com.ktb4.team16.mulo.chat.dto.response.ChatMessageResponse;
import com.ktb4.team16.mulo.chat.entity.ChatMessage;
import com.ktb4.team16.mulo.chat.entity.ChatRoom;
import com.ktb4.team16.mulo.chat.entity.ChatRoomParticipant;
import com.ktb4.team16.mulo.chat.event.ChatMessageDeliveryEvent;
import com.ktb4.team16.mulo.chat.exception.ChatErrorCode;
import com.ktb4.team16.mulo.chat.exception.ChatMessageSendException;
import com.ktb4.team16.mulo.chat.exception.ChatRoomAccessException;
import com.ktb4.team16.mulo.chat.repository.ChatMessageRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatMessageSendTransactionService {

    private static final String MESSAGE_TYPE = "MESSAGE";

    private final ChatRoomAccessService chatRoomAccessService;
    private final ChatMessageRepository messageRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ChatMessageSendTransactionService(
            ChatRoomAccessService chatRoomAccessService,
            ChatMessageRepository messageRepository,
            ApplicationEventPublisher eventPublisher
    ) {
        this.chatRoomAccessService = chatRoomAccessService;
        this.messageRepository = messageRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void send(Long chatRoomId, Long userId, ChatMessageSendRequest request) {
        ChatRoom room = chatRoomAccessService.findRoom(chatRoomId);
        List<ChatRoomParticipant> participants = chatRoomAccessService.findParticipantsForUpdate(chatRoomId);
        if (participants.size() != 2) {
            throw forbidden(request);
        }

        ChatRoomParticipant sender = participants.stream()
                .filter(participant -> participant.getUserId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new ChatRoomAccessException(
                        ChatRoomAccessException.Reason.PARTICIPANT_NOT_FOUND));
        chatRoomAccessService.rejectExpired(room);
        chatRoomAccessService.requireActiveParticipant(sender);

        List<ChatRoomParticipant> activeParticipants = participants.stream()
                .filter(chatRoomAccessService::isActiveParticipant)
                .toList();
        if (activeParticipants.size() != 2) {
            throw forbidden(request);
        }

        Optional<ChatMessage> existingMessage = messageRepository.findByUserIdAndClientMessageId(
                userId, request.clientMessageId().toString());
        if (existingMessage.isPresent()) {
            ChatMessage existing = existingMessage.get();
            if (!matches(existing, chatRoomId, request)) {
                throw ChatMessageSendException.conflict(request.clientMessageId());
            }
            publish(existing, List.of(userId));
            return;
        }

        ChatRoomParticipant peer = activeParticipants.stream()
                .filter(participant -> !participant.getUserId().equals(userId))
                .findFirst()
                .orElseThrow(() -> forbidden(request));
        ChatMessage saved = messageRepository.saveAndFlush(ChatMessage.create(
                room, userId, request.clientMessageId().toString(), request.content()));
        publish(saved, List.of(userId, peer.getUserId()));
    }

    private boolean matches(ChatMessage message, Long chatRoomId, ChatMessageSendRequest request) {
        return message.getChatRoom().getChatRoomId().equals(chatRoomId)
                && message.getContent().equals(request.content());
    }

    private void publish(ChatMessage message, List<Long> recipientUserIds) {
        ChatMessageResponse response = new ChatMessageResponse(
                MESSAGE_TYPE,
                message.getChatRoom().getChatRoomId(),
                message.getChatMessageId(),
                java.util.UUID.fromString(message.getClientMessageId()),
                message.getUserId(),
                message.getContent(),
                ChatTime.toOffsetDateTime(message.getCreatedAt())
        );
        eventPublisher.publishEvent(new ChatMessageDeliveryEvent(response, recipientUserIds));
    }

    private ChatMessageSendException forbidden(ChatMessageSendRequest request) {
        return new ChatMessageSendException(ChatErrorCode.CHAT_SEND_FORBIDDEN, request.clientMessageId());
    }
}
