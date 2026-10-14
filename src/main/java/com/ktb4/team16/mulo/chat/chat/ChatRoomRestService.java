package com.ktb4.team16.mulo.chat.chat;

import com.ktb4.team16.mulo.chat.dto.response.ChatMessageHistoryResponse;
import com.ktb4.team16.mulo.chat.dto.response.ChatRoomDetailResponse;
import com.ktb4.team16.mulo.chat.entity.ChatMessage;
import com.ktb4.team16.mulo.chat.entity.ChatRoom;
import com.ktb4.team16.mulo.chat.entity.ChatRoomParticipant;
import com.ktb4.team16.mulo.chat.exception.ChatApiException;
import com.ktb4.team16.mulo.chat.repository.ChatMessageRepository;
import com.ktb4.team16.mulo.chat.repository.ChatRoomParticipantRepository;
import com.ktb4.team16.mulo.chat.repository.ChatRoomRepository;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatRoomRestService {

    private static final int HISTORY_PAGE_SIZE = 20;
    private static final ZoneId CHAT_TIME_ZONE = ZoneId.of("Asia/Seoul");

    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomParticipantRepository participantRepository;
    private final ChatMessageRepository messageRepository;

    public ChatRoomRestService(
            ChatRoomRepository chatRoomRepository,
            ChatRoomParticipantRepository participantRepository,
            ChatMessageRepository messageRepository
    ) {
        this.chatRoomRepository = chatRoomRepository;
        this.participantRepository = participantRepository;
        this.messageRepository = messageRepository;
    }

    @Transactional
    public void enter(Long chatRoomId, Long userId) {
        ChatRoom room = findRoom(chatRoomId);
        ChatRoomParticipant participant = findParticipantForUpdate(chatRoomId, userId);
        rejectExpired(room, "만료된 채팅방에는 입장할 수 없습니다.");
        if (participant.getLeftAt() != null) {
            throw forbidden("CHAT_REENTRY_FORBIDDEN", "퇴장한 채팅방에는 다시 입장할 수 없습니다.");
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

        Cursor decodedCursor = decodeCursor(cursor);
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
                ? encodeCursor(newestFirst.getLast()) : null;
        return new ChatMessageHistoryResponse(messages, nextCursor, page.hasNext());
    }

    @Transactional
    public void leave(Long chatRoomId, Long userId) {
        ChatRoom room = findRoom(chatRoomId);
        ChatRoomParticipant participant = findParticipantForUpdate(chatRoomId, userId);
        rejectExpired(room, "만료된 채팅방에는 접근할 수 없습니다.");
        if (participant.getLeftAt() != null) {
            throw new ChatApiException(HttpStatus.CONFLICT, "CHAT_ALREADY_LEFT", "이미 퇴장한 채팅방입니다.");
        }
        participant.leave();
    }

    private ChatRoom findRoom(Long chatRoomId) {
        return chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> notFound());
    }

    private ChatRoomParticipant findParticipant(Long chatRoomId, Long userId) {
        return participantRepository.findByChatRoomChatRoomIdAndUserId(chatRoomId, userId)
                .orElseThrow(this::notFound);
    }

    private ChatRoomParticipant findParticipantForUpdate(Long chatRoomId, Long userId) {
        return participantRepository.findByChatRoomChatRoomIdAndUserIdForUpdate(chatRoomId, userId)
                .orElseThrow(this::notFound);
    }

    private void requireActiveParticipant(ChatRoomParticipant participant) {
        if (participant.getLeftAt() != null) {
            throw forbidden("CHAT_ALREADY_LEFT", "이미 퇴장한 채팅방입니다.");
        }
        if (participant.getJoinedAt() == null) {
            throw notFound();
        }
    }

    private void rejectExpired(ChatRoom room, String message) {
        if (!room.getExpiresAt().isAfter(LocalDateTime.now(CHAT_TIME_ZONE))) {
            throw forbidden("CHAT_ROOM_EXPIRED", message);
        }
    }

    private Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = value.split("\\|", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException();
            }
            long chatMessageId = Long.parseLong(parts[1]);
            if (chatMessageId <= 0) {
                throw new IllegalArgumentException();
            }
            return new Cursor(LocalDateTime.parse(parts[0]), chatMessageId);
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw new ChatApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "올바른 커서를 입력해주세요.");
        }
    }

    private OffsetDateTime toKstOffsetDateTime(LocalDateTime value) {
        return value.atZone(CHAT_TIME_ZONE).toOffsetDateTime();
    }

    private String encodeCursor(ChatMessage message) {
        String value = message.getCreatedAt() + "|" + message.getChatMessageId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private ChatApiException notFound() {
        return new ChatApiException(HttpStatus.NOT_FOUND, "CHAT_ROOM_NOT_FOUND", "채팅방을 찾을 수 없습니다.");
    }

    private ChatApiException forbidden(String code, String message) {
        return new ChatApiException(HttpStatus.FORBIDDEN, code, message);
    }

    private record Cursor(LocalDateTime createdAt, Long chatMessageId) {
    }
}
