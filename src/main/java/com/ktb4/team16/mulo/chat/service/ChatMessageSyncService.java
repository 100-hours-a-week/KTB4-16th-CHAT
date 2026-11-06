package com.ktb4.team16.mulo.chat.service;

import com.ktb4.team16.mulo.chat.ChatTime;
import com.ktb4.team16.mulo.chat.dto.response.*;
import com.ktb4.team16.mulo.chat.exception.*;
import com.ktb4.team16.mulo.chat.repository.ChatMessageRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ChatMessageSyncService {
    private final ChatRoomRestService rooms;
    private final ChatMessageRepository messages;

    /** 기존 활성 참가자 검증과 동일한 writer DB를 사용한다. */
    public ChatMessageSyncService(ChatRoomRestService rooms, ChatMessageRepository messages) {
        this.rooms = rooms;
        this.messages = messages;
    }

    /** ID 발급 전 참가자 잠금으로 직렬화된 방의 커밋 메시지만 고정 범위로 복구한다. */
    @Transactional(readOnly = true)
    public ChatMessageSyncResponse sync(Long roomId, Long userId, Long after, Long through) {
        var detail = rooms.getDetail(roomId, userId);
        Long found = messages.findMaxMessageIdByRoomId(roomId);
        long max = found == null ? 0 : found;
        if (after == null) {
            if (through != null) throw invalid();
            return new ChatMessageSyncResponse(List.of(), max, max, false);
        }
        long upper = through == null ? max : through;
        if (after < 0 || upper < after || upper > max) throw invalid();
        var rows = messages.findSyncRange(roomId, after, upper, PageRequest.of(0, 21));
        boolean more = rows.size() > 20;
        var participants =
                detail.participants().stream()
                        .collect(
                                Collectors.toMap(
                                        ChatRoomDetailResponse.Participant::userId,
                                        Function.identity()));
        var page =
                rows.stream()
                        .limit(20)
                        .map(
                                m -> {
                                    var p = participants.get(m.getUserId());
                                    return new ChatMessageHistoryResponse.Message(
                                            m.getChatMessageId(),
                                            m.getClientMessageId(),
                                            m.getUserId(),
                                            p == null ? "" : p.nickname(),
                                            m.getContent(),
                                            ChatTime.toOffsetDateTime(m.getCreatedAt()),
                                            p == null ? null : p.voteChoice());
                                })
                        .toList();
        return new ChatMessageSyncResponse(
                page, upper, more ? page.getLast().chatMessageId() : upper, more);
    }

    /** 입력 범위 오류를 기존 커서 오류 계약으로 반환한다. */
    private ChatApiException invalid() {
        return new ChatApiException(ChatErrorCode.INVALID_CURSOR);
    }
}
