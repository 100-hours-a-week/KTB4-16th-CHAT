package com.ktb4.team16.mulo.chat.chat;

import com.ktb4.team16.mulo.chat.ChatTime;
import com.ktb4.team16.mulo.chat.entity.ChatRoom;
import com.ktb4.team16.mulo.chat.entity.ChatRoomParticipant;
import com.ktb4.team16.mulo.chat.repository.ChatRoomParticipantRepository;
import com.ktb4.team16.mulo.chat.repository.ChatRoomRepository;
import java.time.Clock;
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
