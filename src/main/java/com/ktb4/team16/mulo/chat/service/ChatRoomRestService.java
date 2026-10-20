package com.ktb4.team16.mulo.chat.service;

import com.ktb4.team16.mulo.chat.ChatTime;
import com.ktb4.team16.mulo.chat.cursor.ChatMessageCursorCodec;
import com.ktb4.team16.mulo.chat.dto.response.ChatMessageHistoryResponse;
import com.ktb4.team16.mulo.chat.dto.response.ChatParticipantLeftResponse;
import com.ktb4.team16.mulo.chat.dto.response.ChatRoomDetailResponse;
import com.ktb4.team16.mulo.chat.entity.ChatMessage;
import com.ktb4.team16.mulo.chat.entity.ChatRoom;
import com.ktb4.team16.mulo.chat.entity.ChatRoomParticipant;
import com.ktb4.team16.mulo.chat.event.ChatParticipantLeftDeliveryEvent;
import com.ktb4.team16.mulo.chat.exception.ChatApiException;
import com.ktb4.team16.mulo.chat.exception.ChatErrorCode;
import com.ktb4.team16.mulo.chat.exception.ChatRoomAccessException;
import com.ktb4.team16.mulo.chat.repository.ChatMessageRepository;
import com.ktb4.team16.mulo.chat.repository.ChatRoomParticipantRepository;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatRoomRestService {

    private static final int HISTORY_PAGE_SIZE = 20;
    private final ChatRoomAccessService accessService;
    private final ChatRoomParticipantRepository participantRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatMessageCursorCodec cursorCodec;
    private final ApplicationEventPublisher eventPublisher;

    public ChatRoomRestService(
            ChatRoomAccessService accessService,
            ChatRoomParticipantRepository participantRepository,
            ChatMessageRepository messageRepository,
            ChatMessageCursorCodec cursorCodec,
            ApplicationEventPublisher eventPublisher
    ) {
        this.accessService = accessService;
        this.participantRepository = participantRepository;
        this.messageRepository = messageRepository;
        this.cursorCodec = cursorCodec;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void enter(Long chatRoomId, Long userId) {
        ChatRoom room = findRoom(chatRoomId);
        ChatRoomParticipant participant = findParticipantForUpdate(chatRoomId, userId);
        rejectExpired(room, "만료된 채팅방에는 입장할 수 없습니다.");
        if (participant.getLeftAt() != null) {
            throw new ChatApiException(ChatErrorCode.CHAT_REENTRY_FORBIDDEN);
        }
        participant.enter();
    }

    @Transactional(readOnly = true)
    public ChatRoomDetailResponse getDetail(Long chatRoomId, Long userId) {
        ChatRoom room = findRoom(chatRoomId);
        ChatRoomParticipant participant = findParticipant(chatRoomId, userId);
        rejectExpired(room, "만료된 채팅방은 조회할 수 없습니다.");
        requireActiveParticipant(participant);
        List<ChatRoomParticipant> participants = participantRepository.findAllByChatRoomChatRoomId(chatRoomId);
        return new ChatRoomDetailResponse(chatRoomId, participants.stream()
                .map(value -> new ChatRoomDetailResponse.Participant(value.getUserId(), value.getNickname()))
                .toList());
    }

    @Transactional(readOnly = true)
    public ChatMessageHistoryResponse getHistory(Long chatRoomId, Long userId, String cursor) {
        ChatRoom room = findRoom(chatRoomId);
        ChatRoomParticipant participant = findParticipant(chatRoomId, userId);
        rejectExpired(room, "만료된 채팅방의 메시지는 조회할 수 없습니다.");
        requireActiveParticipant(participant);

        ChatMessageCursorCodec.Cursor decodedCursor = cursorCodec.decode(cursor);
        Slice<ChatMessage> page = messageRepository.findHistory(
                chatRoomId,
                decodedCursor == null ? null : decodedCursor.createdAt(),
                decodedCursor == null ? null : decodedCursor.chatMessageId(),
                PageRequest.of(0, HISTORY_PAGE_SIZE)
        );
        Map<Long, String> nicknames = participantRepository.findAllByChatRoomChatRoomId(chatRoomId).stream()
                .collect(java.util.stream.Collectors.toMap(ChatRoomParticipant::getUserId,
                        ChatRoomParticipant::getNickname));
        List<ChatMessage> newestFirst = page.getContent();
        List<ChatMessageHistoryResponse.Message> messages = newestFirst.reversed().stream()
                .map(message -> new ChatMessageHistoryResponse.Message(
                        message.getChatMessageId(), message.getClientMessageId(), message.getUserId(),
                        nicknames.get(message.getUserId()), message.getContent(), toKstOffsetDateTime(message.getCreatedAt())))
                .toList();
        String nextCursor = page.hasNext() && !newestFirst.isEmpty()
                ? cursorCodec.encode(newestFirst.getLast().getCreatedAt(), newestFirst.getLast().getChatMessageId()) : null;
        return new ChatMessageHistoryResponse(messages, nextCursor, page.hasNext());
    }

    @Transactional
    public void leave(Long chatRoomId, Long userId) {
        ChatRoom room = findRoom(chatRoomId);
        List<ChatRoomParticipant> participants = accessService.findParticipantsForUpdate(chatRoomId);
        ChatRoomParticipant participant = participants.stream()
                .filter(value -> value.getUserId().equals(userId))
                .findFirst()
                .orElseThrow(this::notFound);
        rejectExpired(room, "만료된 채팅방에는 접근할 수 없습니다.");
        if (participant.getLeftAt() != null) {
            throw new ChatApiException(ChatErrorCode.CHAT_ALREADY_LEFT);
        }
        List<Long> recipientUserIds = participants.stream()
                .filter(accessService::isActiveParticipant)
                .map(ChatRoomParticipant::getUserId)
                .filter(participantUserId -> !participantUserId.equals(userId))
                .toList();
        participant.leave();
        if (!recipientUserIds.isEmpty()) {
            ChatParticipantLeftResponse response = new ChatParticipantLeftResponse(
                    "PARTICIPANT_LEFT",
                    chatRoomId,
                    participant.getUserId(),
                    participant.getNickname(),
                    ChatTime.toOffsetDateTime(participant.getLeftAt())
            );
            eventPublisher.publishEvent(new ChatParticipantLeftDeliveryEvent(response, recipientUserIds));
        }
    }

    private OffsetDateTime toKstOffsetDateTime(LocalDateTime value) {
        return ChatTime.toOffsetDateTime(value);
    }

    private ChatRoom findRoom(Long chatRoomId) {
        try {
            return accessService.findRoom(chatRoomId);
        } catch (ChatRoomAccessException exception) {
            throw notFound();
        }
    }

    private ChatRoomParticipant findParticipant(Long chatRoomId, Long userId) {
        try {
            return accessService.findParticipant(chatRoomId, userId);
        } catch (ChatRoomAccessException exception) {
            throw notFound();
        }
    }

    private ChatRoomParticipant findParticipantForUpdate(Long chatRoomId, Long userId) {
        try {
            return accessService.findParticipantForUpdate(chatRoomId, userId);
        } catch (ChatRoomAccessException exception) {
            throw notFound();
        }
    }

    private void requireActiveParticipant(ChatRoomParticipant participant) {
        try {
            accessService.requireActiveParticipant(participant);
        } catch (ChatRoomAccessException exception) {
            if (exception.getReason() == ChatRoomAccessException.Reason.PARTICIPANT_LEFT) {
                throw new ChatApiException(ChatErrorCode.CHAT_ALREADY_LEFT_FORBIDDEN);
            }
            throw notFound();
        }
    }

    private void rejectExpired(ChatRoom room, String message) {
        try {
            accessService.rejectExpired(room);
        } catch (ChatRoomAccessException exception) {
            if (exception.getReason() == ChatRoomAccessException.Reason.ROOM_EXPIRED) {
                throw new ChatApiException(ChatErrorCode.CHAT_ROOM_EXPIRED, message);
            }
            throw exception;
        }
    }

    private ChatApiException notFound() {
        return new ChatApiException(ChatErrorCode.CHAT_ROOM_NOT_FOUND);
    }
}
