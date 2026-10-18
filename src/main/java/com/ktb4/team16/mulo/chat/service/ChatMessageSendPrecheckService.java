package com.ktb4.team16.mulo.chat.service;

import com.ktb4.team16.mulo.chat.dto.request.ChatMessageSendRequest;
import com.ktb4.team16.mulo.chat.entity.ChatMessage;
import com.ktb4.team16.mulo.chat.entity.ChatRoom;
import com.ktb4.team16.mulo.chat.entity.ChatRoomParticipant;
import com.ktb4.team16.mulo.chat.exception.ChatMessageSendException;
import com.ktb4.team16.mulo.chat.exception.ChatRoomAccessException;
import com.ktb4.team16.mulo.chat.repository.ChatMessageRepository;
import com.ktb4.team16.mulo.chat.repository.ChatRoomParticipantRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatMessageSendPrecheckService {

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
        ChatRoom room = chatRoomAccessService.findRoom(chatRoomId);
        List<ChatRoomParticipant> participants = participantRepository.findAllByChatRoomChatRoomId(chatRoomId);
        if (participants.size() != 2) {
            throw new ChatRoomAccessException(ChatRoomAccessException.Reason.PARTICIPANT_NOT_FOUND);
        }

        ChatRoomParticipant sender = participants.stream()
                .filter(participant -> participant.getUserId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new ChatRoomAccessException(
                        ChatRoomAccessException.Reason.PARTICIPANT_NOT_FOUND));
        chatRoomAccessService.rejectExpired(room);
        chatRoomAccessService.requireActiveParticipant(sender);
        if (participants.stream().anyMatch(participant -> !chatRoomAccessService.isActiveParticipant(participant))) {
            throw new ChatRoomAccessException(ChatRoomAccessException.Reason.PARTICIPANT_LEFT);
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
}
