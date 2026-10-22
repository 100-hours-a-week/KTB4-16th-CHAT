package com.ktb4.team16.mulo.chat.service;

import com.ktb4.team16.mulo.chat.ChatTime;
import com.ktb4.team16.mulo.chat.dto.response.ChatParticipantNicknameChangedResponse;
import com.ktb4.team16.mulo.chat.dto.response.UpdateNicknameResponse;
import com.ktb4.team16.mulo.chat.entity.ChatRoom;
import com.ktb4.team16.mulo.chat.entity.ChatRoomParticipant;
import com.ktb4.team16.mulo.chat.event.ChatParticipantNicknameChangedDeliveryEvent;
import com.ktb4.team16.mulo.chat.repository.ChatRoomParticipantRepository;
import com.ktb4.team16.mulo.chat.repository.ChatRoomRepository;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatParticipantNicknameService {

    private final ChatRoomParticipantRepository participantRepository;
    private final ChatRoomRepository roomRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public ChatParticipantNicknameService(
            ChatRoomParticipantRepository participantRepository,
            ChatRoomRepository roomRepository,
            ApplicationEventPublisher eventPublisher,
            Clock clock
    ) {
        this.participantRepository = participantRepository;
        this.roomRepository = roomRepository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional
    public UpdateNicknameResponse updateNickname(Long userId, String nickname) {
        List<ChatRoomParticipant> participants = participantRepository.findAllByUserIdForUpdate(userId);
        Set<Long> changedRoomIds = new LinkedHashSet<>();
        int updatedCount = 0;
        for (ChatRoomParticipant participant : participants) {
            if (!participant.getNickname().equals(nickname)) {
                participant.changeNickname(nickname);
                changedRoomIds.add(participant.getChatRoom().getChatRoomId());
                updatedCount++;
            }
        }
        if (changedRoomIds.isEmpty()) {
            return new UpdateNicknameResponse(userId, 0);
        }

        Map<Long, ChatRoom> rooms = new LinkedHashMap<>();
        roomRepository.findAllById(changedRoomIds).forEach(room -> rooms.put(room.getChatRoomId(), room));
        Map<Long, List<ChatRoomParticipant>> activeParticipantsByRoom = participantRepository
                .findAllByChatRoomChatRoomIdInAndJoinedAtIsNotNullAndLeftAtIsNull(changedRoomIds)
                .stream()
                .collect(Collectors.groupingBy(participant -> participant.getChatRoom().getChatRoomId()));

        var now = ChatTime.now(clock);
        for (Long chatRoomId : changedRoomIds) {
            ChatRoom room = rooms.get(chatRoomId);
            List<ChatRoomParticipant> activeParticipants = activeParticipantsByRoom.getOrDefault(chatRoomId, List.of());
            if (room == null || !room.getExpiresAt().isAfter(now) || activeParticipants.isEmpty()) {
                continue;
            }
            List<Long> recipientUserIds = activeParticipants.stream()
                    .map(ChatRoomParticipant::getUserId)
                    .distinct()
                    .toList();
            var response = new ChatParticipantNicknameChangedResponse(
                    "PARTICIPANT_NICKNAME_CHANGED", chatRoomId, userId, nickname);
            eventPublisher.publishEvent(new ChatParticipantNicknameChangedDeliveryEvent(response, recipientUserIds));
        }
        return new UpdateNicknameResponse(userId, updatedCount);
    }
}
