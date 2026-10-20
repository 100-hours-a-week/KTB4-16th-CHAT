package com.ktb4.team16.mulo.chat.service;

import com.ktb4.team16.mulo.chat.dto.request.ChatMessageSendRequest;
import com.ktb4.team16.mulo.chat.entity.ChatMessage;
import com.ktb4.team16.mulo.chat.entity.ChatRoom;
import com.ktb4.team16.mulo.chat.entity.ChatRoomParticipant;
import com.ktb4.team16.mulo.chat.exception.ChatMessageSendException;
import com.ktb4.team16.mulo.chat.exception.ChatSendAccessException;
import com.ktb4.team16.mulo.chat.exception.ChatSendDiagnostic;
import com.ktb4.team16.mulo.chat.exception.ChatRoomAccessException;
import com.ktb4.team16.mulo.chat.repository.ChatMessageRepository;
import com.ktb4.team16.mulo.chat.repository.ChatRoomParticipantRepository;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatMessageSendPrecheckService {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageSendPrecheckService.class);

    private final ChatRoomAccessService chatRoomAccessService;
    private final ChatRoomParticipantRepository participantRepository;
    private final ChatMessageRepository messageRepository;

    public ChatMessageSendPrecheckService(
            ChatRoomAccessService chatRoomAccessService,
            ChatRoomParticipantRepository participantRepository,
            ChatMessageRepository messageRepository
    ) {
        this.chatRoomAccessService = chatRoomAccessService;
        this.participantRepository = participantRepository;
        this.messageRepository = messageRepository;
    }

    /**
     * 권한과 기존 멱등 결과를 잠금 없이 확인한다. 최종 저장 권한은 저장 트랜잭션에서 다시 검증한다.
     */
    @Transactional(readOnly = true)
    public boolean requiresModeration(Long chatRoomId, Long userId, ChatMessageSendRequest request) {
        ChatRoom room;
        try {
            room = chatRoomAccessService.findRoom(chatRoomId);
        } catch (ChatRoomAccessException exception) {
            ChatSendDiagnostic diagnostic = diagnostic(
                    ChatSendDiagnostic.Stage.PRECHECK, ChatSendDiagnostic.Reason.ROOM_NOT_FOUND);
            logDenied(chatRoomId, userId, request, diagnostic.reason().name(), null);
            throw new ChatSendAccessException(exception.getReason(), diagnostic);
        }
        List<ChatRoomParticipant> participants = participantRepository.findAllByChatRoomChatRoomId(chatRoomId);
        if (participants.size() != 2) {
            ChatSendDiagnostic diagnostic = diagnostic(
                    ChatSendDiagnostic.Stage.PRECHECK, ChatSendDiagnostic.Reason.PARTICIPANT_COUNT_MISMATCH);
            logDenied(chatRoomId, userId, request, diagnostic.reason().name(), participants);
            throw new ChatSendAccessException(ChatRoomAccessException.Reason.PARTICIPANT_NOT_FOUND, diagnostic);
        }

        ChatRoomParticipant sender = participants.stream()
                .filter(participant -> participant.getUserId().equals(userId))
                .findFirst()
                .orElse(null);
        if (sender == null) {
            ChatSendDiagnostic diagnostic = diagnostic(
                    ChatSendDiagnostic.Stage.PRECHECK, ChatSendDiagnostic.Reason.SENDER_NOT_FOUND);
            logDenied(chatRoomId, userId, request, diagnostic.reason().name(), participants);
            throw new ChatSendAccessException(ChatRoomAccessException.Reason.PARTICIPANT_NOT_FOUND, diagnostic);
        }
        try {
            chatRoomAccessService.rejectExpired(room);
        } catch (ChatRoomAccessException exception) {
            ChatSendDiagnostic diagnostic = diagnostic(
                    ChatSendDiagnostic.Stage.PRECHECK, ChatSendDiagnostic.Reason.ROOM_EXPIRED);
            logDenied(chatRoomId, userId, request, diagnostic.reason().name(), participants);
            throw new ChatSendAccessException(exception.getReason(), diagnostic);
        }
        try {
            chatRoomAccessService.requireActiveParticipant(sender);
        } catch (ChatRoomAccessException exception) {
            ChatSendDiagnostic.Reason reason = sender.getLeftAt() != null
                    ? ChatSendDiagnostic.Reason.SENDER_LEFT : ChatSendDiagnostic.Reason.SENDER_NOT_JOINED;
            ChatSendDiagnostic diagnostic = diagnostic(ChatSendDiagnostic.Stage.PRECHECK, reason);
            logDenied(chatRoomId, userId, request, diagnostic.reason().name(), participants);
            throw new ChatSendAccessException(exception.getReason(), diagnostic);
        }
        if (participants.stream().anyMatch(participant -> !chatRoomAccessService.isActiveParticipant(participant))) {
            ChatSendDiagnostic diagnostic = diagnostic(
                    ChatSendDiagnostic.Stage.PRECHECK, ChatSendDiagnostic.Reason.PEER_NOT_ACTIVE);
            logDenied(chatRoomId, userId, request, diagnostic.reason().name(), participants);
            throw new ChatSendAccessException(ChatRoomAccessException.Reason.PARTICIPANT_LEFT, diagnostic);
        }

        Optional<ChatMessage> existing = messageRepository.findByUserIdAndClientMessageId(
                userId, request.clientMessageId().toString());
        if (existing.isEmpty()) {
            return true;
        }

        ChatMessage message = existing.get();
        if (!message.getChatRoom().getChatRoomId().equals(chatRoomId)
                || !message.getContent().equals(request.content())) {
            throw ChatMessageSendException.conflict(request.clientMessageId());
        }
        return false;
    }

    private void logDenied(
            Long chatRoomId,
            Long userId,
            ChatMessageSendRequest request,
            String reason,
            List<ChatRoomParticipant> participants
    ) {
        ChatSendDiagnosticLogger.warn(log, chatRoomId, userId,
                request == null ? null : request.clientMessageId(), "precheck", reason, participants);
    }

    private ChatSendDiagnostic diagnostic(ChatSendDiagnostic.Stage stage, ChatSendDiagnostic.Reason reason) {
        return new ChatSendDiagnostic(stage, reason);
    }
}
