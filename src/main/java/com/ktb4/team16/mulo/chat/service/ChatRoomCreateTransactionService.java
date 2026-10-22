package com.ktb4.team16.mulo.chat.service;

import com.ktb4.team16.mulo.chat.ChatTime;
import com.ktb4.team16.mulo.chat.dto.request.CreateChatRoomRequest;
import com.ktb4.team16.mulo.chat.dto.response.CreateChatRoomResponse;
import com.ktb4.team16.mulo.chat.entity.ChatRoom;
import com.ktb4.team16.mulo.chat.entity.ChatRoomParticipant;
import com.ktb4.team16.mulo.chat.exception.ChatApiException;
import com.ktb4.team16.mulo.chat.exception.ChatErrorCode;
import com.ktb4.team16.mulo.chat.repository.ChatRoomParticipantRepository;
import com.ktb4.team16.mulo.chat.repository.ChatRoomRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatRoomCreateTransactionService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomParticipantRepository participantRepository;
    private final Clock clock;

    public ChatRoomCreateTransactionService(
            ChatRoomRepository chatRoomRepository,
            ChatRoomParticipantRepository participantRepository,
            Clock clock
    ) {
        this.chatRoomRepository = chatRoomRepository;
        this.participantRepository = participantRepository;
        this.clock = clock;
    }

    @Transactional
    public ChatRoomCreateService.Result create(CreateChatRoomRequest request) {
        var existingRoom = chatRoomRepository.findByMatchId(request.matchId());
        if (existingRoom.isPresent()) {
            return resolveExistingRoom(request, existingRoom.get());
        }

        LocalDateTime expiresAt = toStoredExpiresAt(request);
        if (!expiresAt.isAfter(ChatTime.now(clock))) {
            throw new ChatApiException(ChatErrorCode.CHAT_ROOM_CREATE_EXPIRED);
        }

        ChatRoom room = chatRoomRepository.saveAndFlush(
                ChatRoom.create(request.matchId(), ChatTime.toOffsetDateTime(expiresAt)));
        List<ChatRoomParticipant> participants = request.participants().stream()
                .map(participant -> ChatRoomParticipant.create(room, participant.userId(), participant.nickname()))
                .toList();
        participantRepository.saveAll(participants);
        return new ChatRoomCreateService.Result(new CreateChatRoomResponse(room.getChatRoomId()), true);
    }

    public ChatRoomCreateService.Result resolveExisting(CreateChatRoomRequest request) {
        ChatRoom room = chatRoomRepository.findByMatchId(request.matchId())
                .orElseThrow(() -> new ChatApiException(ChatErrorCode.INTERNAL_SERVER_ERROR));
        return resolveExistingRoom(request, room);
    }

    private ChatRoomCreateService.Result resolveExistingRoom(CreateChatRoomRequest request, ChatRoom room) {
        List<ChatRoomParticipant> participants = participantRepository.findAllByChatRoomChatRoomId(room.getChatRoomId());
        LocalDateTime requestedExpiresAt = toStoredExpiresAt(request);
        boolean sameParticipants = participants.size() == 2 && request.participants().stream().allMatch(input ->
                participants.stream().anyMatch(saved -> saved.getUserId().equals(input.userId())));
        if (!sameParticipants || !room.getExpiresAt().equals(requestedExpiresAt)) {
            throw new ChatApiException(ChatErrorCode.CHAT_ROOM_CONFLICT);
        }
        return new ChatRoomCreateService.Result(new CreateChatRoomResponse(room.getChatRoomId()), false);
    }

    private LocalDateTime toStoredExpiresAt(CreateChatRoomRequest request) {
        return request.expiresAt().atZoneSameInstant(ChatTime.KST).toLocalDateTime()
                .truncatedTo(ChronoUnit.SECONDS);
    }
}
