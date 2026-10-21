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
import com.ktb4.team16.mulo.chat.exception.ChatSendAccessException;
import com.ktb4.team16.mulo.chat.exception.ChatSendDiagnostic;
import com.ktb4.team16.mulo.chat.exception.ChatRoomAccessException;
import com.ktb4.team16.mulo.chat.repository.ChatMessageRepository;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatMessageSendTransactionService {

    private static final String MESSAGE_TYPE = "MESSAGE";
    private static final Logger log = LoggerFactory.getLogger(ChatMessageSendTransactionService.class);

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
        ChatRoom room;
        try {
            room = chatRoomAccessService.findRoom(chatRoomId);
        } catch (ChatRoomAccessException exception) {
            ChatSendDiagnostic diagnostic = diagnostic(
                    ChatSendDiagnostic.Stage.TRANSACTION, ChatSendDiagnostic.Reason.ROOM_NOT_FOUND);
            logDenied(chatRoomId, userId, request, diagnostic.reason().name(), null);
            throw new ChatSendAccessException(exception.getReason(), diagnostic);
        }
        List<ChatRoomParticipant> participants;
        try {
            participants = chatRoomAccessService.findParticipantsForUpdate(chatRoomId);
        } catch (ChatRoomAccessException exception) {
            ChatSendDiagnostic diagnostic = diagnostic(
                    ChatSendDiagnostic.Stage.TRANSACTION,
                    ChatSendDiagnostic.Reason.PARTICIPANT_ROW_NOT_FOUND_DURING_LOCK);
            logDenied(chatRoomId, userId, request, diagnostic.reason().name(), null);
            throw new ChatSendAccessException(exception.getReason(), diagnostic);
        }
        if (participants.size() != 2) {
            ChatSendDiagnostic diagnostic = diagnostic(
                    ChatSendDiagnostic.Stage.TRANSACTION, ChatSendDiagnostic.Reason.PARTICIPANT_COUNT_MISMATCH);
            logDenied(chatRoomId, userId, request, diagnostic.reason().name(), participants);
            throw new ChatSendAccessException(ChatRoomAccessException.Reason.PARTICIPANT_NOT_FOUND, diagnostic);
        }

        ChatRoomParticipant sender = participants.stream()
                .filter(participant -> participant.getUserId().equals(userId))
                .findFirst()
                .orElse(null);
        if (sender == null) {
            ChatSendDiagnostic diagnostic = diagnostic(
                    ChatSendDiagnostic.Stage.TRANSACTION, ChatSendDiagnostic.Reason.SENDER_NOT_FOUND);
            logDenied(chatRoomId, userId, request, diagnostic.reason().name(), participants);
            throw new ChatSendAccessException(ChatRoomAccessException.Reason.PARTICIPANT_NOT_FOUND, diagnostic);
        }
        try {
            chatRoomAccessService.rejectExpired(room);
        } catch (ChatRoomAccessException exception) {
            ChatSendDiagnostic diagnostic = diagnostic(
                    ChatSendDiagnostic.Stage.TRANSACTION, ChatSendDiagnostic.Reason.ROOM_EXPIRED);
            logDenied(chatRoomId, userId, request, diagnostic.reason().name(), participants);
            throw new ChatSendAccessException(exception.getReason(), diagnostic);
        }
        try {
            chatRoomAccessService.requireActiveParticipant(sender);
        } catch (ChatRoomAccessException exception) {
            ChatSendDiagnostic.Reason reason = sender.getLeftAt() != null
                    ? ChatSendDiagnostic.Reason.SENDER_LEFT : ChatSendDiagnostic.Reason.SENDER_NOT_JOINED;
            ChatSendDiagnostic diagnostic = diagnostic(ChatSendDiagnostic.Stage.TRANSACTION, reason);
            logDenied(chatRoomId, userId, request, diagnostic.reason().name(), participants);
            throw new ChatSendAccessException(exception.getReason(), diagnostic);
        }

        ChatRoomParticipant peerParticipant = participants.stream()
                .filter(participant -> !participant.getUserId().equals(userId))
                .findFirst()
                .orElse(null);
        if (isNotYetJoined(peerParticipant)) {
            ChatSendDiagnostic diagnostic = diagnostic(
                    ChatSendDiagnostic.Stage.TRANSACTION, ChatSendDiagnostic.Reason.PEER_NOT_ACTIVE);
            logDenied(chatRoomId, userId, request, diagnostic.reason().name(), participants);
            throw new ChatSendAccessException(ChatRoomAccessException.Reason.PEER_NOT_JOINED, diagnostic);
        }

        List<ChatRoomParticipant> activeParticipants = participants.stream()
                .filter(chatRoomAccessService::isActiveParticipant)
                .toList();
        if (activeParticipants.size() != 2) {
            ChatSendDiagnostic diagnostic = diagnostic(
                    ChatSendDiagnostic.Stage.TRANSACTION, ChatSendDiagnostic.Reason.PEER_NOT_ACTIVE);
            logDenied(chatRoomId, userId, request, diagnostic.reason().name(), participants);
            throw new ChatSendAccessException(ChatRoomAccessException.Reason.PARTICIPANT_LEFT, diagnostic);
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
                .orElse(null);
        if (peer == null) {
            ChatSendDiagnostic diagnostic = diagnostic(
                    ChatSendDiagnostic.Stage.TRANSACTION, ChatSendDiagnostic.Reason.PEER_NOT_FOUND_AFTER_ACTIVE_CHECK);
            logDenied(chatRoomId, userId, request, diagnostic.reason().name(), participants);
            throw forbidden(request, diagnostic);
        }
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

    private ChatMessageSendException forbidden(ChatMessageSendRequest request, ChatSendDiagnostic diagnostic) {
        return new ChatMessageSendException(ChatErrorCode.CHAT_SEND_FORBIDDEN, request.clientMessageId(), diagnostic);
    }

    private boolean isNotYetJoined(ChatRoomParticipant participant) {
        return participant != null && participant.getJoinedAt() == null && participant.getLeftAt() == null;
    }

    private ChatSendDiagnostic diagnostic(ChatSendDiagnostic.Stage stage, ChatSendDiagnostic.Reason reason) {
        return new ChatSendDiagnostic(stage, reason);
    }

    private void logDenied(
            Long chatRoomId,
            Long userId,
            ChatMessageSendRequest request,
            String reason,
            List<ChatRoomParticipant> participants
    ) {
        ChatSendDiagnosticLogger.warn(log, chatRoomId, userId,
                request == null ? null : request.clientMessageId(), "transaction", reason, participants);
    }
}
