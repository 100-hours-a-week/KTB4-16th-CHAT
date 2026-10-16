package com.ktb4.team16.mulo.chat.service;

import com.ktb4.team16.mulo.chat.ChatTime;
import com.ktb4.team16.mulo.chat.entity.ChatRoom;
import com.ktb4.team16.mulo.chat.entity.ChatRoomParticipant;
import com.ktb4.team16.mulo.chat.exception.ChatRoomAccessException;
import com.ktb4.team16.mulo.chat.repository.ChatRoomParticipantRepository;
import com.ktb4.team16.mulo.chat.repository.ChatRoomRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ChatRoomAccessService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomParticipantRepository participantRepository;
    private final Clock clock;

    public ChatRoomAccessService(
            ChatRoomRepository chatRoomRepository,
            ChatRoomParticipantRepository participantRepository,
            Clock clock
    ) {
        this.chatRoomRepository = chatRoomRepository;
        this.participantRepository = participantRepository;
        this.clock = clock;
    }

    public ChatRoom findRoom(Long chatRoomId) {
        return chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new ChatRoomAccessException(ChatRoomAccessException.Reason.ROOM_NOT_FOUND));
    }

    public ChatRoomParticipant findParticipant(Long chatRoomId, Long userId) {
        return participantRepository.findByChatRoomChatRoomIdAndUserId(chatRoomId, userId)
                .orElseThrow(() -> new ChatRoomAccessException(ChatRoomAccessException.Reason.PARTICIPANT_NOT_FOUND));
    }

    public ChatRoomParticipant findParticipantForUpdate(Long chatRoomId, Long userId) {
        return participantRepository.findByChatRoomChatRoomIdAndUserIdForUpdate(chatRoomId, userId)
                .orElseThrow(() -> new ChatRoomAccessException(ChatRoomAccessException.Reason.PARTICIPANT_NOT_FOUND));
    }

    public List<ChatRoomParticipant> findParticipantsForUpdate(Long chatRoomId) {
        return participantRepository.findParticipantUserIdsByChatRoomIdOrderByUserId(chatRoomId).stream()
                .map(userId -> findParticipantForUpdate(chatRoomId, userId))
                .toList();
    }

    public boolean isActiveParticipant(ChatRoomParticipant participant) {
        return participant.getJoinedAt() != null && participant.getLeftAt() == null;
    }

    public void requireActiveParticipant(ChatRoomParticipant participant) {
        if (participant.getLeftAt() != null) {
            throw new ChatRoomAccessException(ChatRoomAccessException.Reason.PARTICIPANT_LEFT);
        }
        if (participant.getJoinedAt() == null) {
            throw new ChatRoomAccessException(ChatRoomAccessException.Reason.PARTICIPANT_NOT_JOINED);
        }
    }

    public void rejectExpired(ChatRoom room) {
        if (!room.getExpiresAt().isAfter(ChatTime.now(clock))) {
            throw new ChatRoomAccessException(ChatRoomAccessException.Reason.ROOM_EXPIRED);
        }
    }
}
