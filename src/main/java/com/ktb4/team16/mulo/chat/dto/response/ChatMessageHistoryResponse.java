package com.ktb4.team16.mulo.chat.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

public record ChatMessageHistoryResponse(List<Message> messages, String nextCursor, boolean hasNext) {

    public record Message(
            Long chatMessageId,
            String clientMessageId,
            Long userId,
            String nickname,
            String content,
            OffsetDateTime createdAt,
            String voteChoice
    ) {
        public Message(Long chatMessageId, String clientMessageId, Long userId, String nickname,
                       String content, OffsetDateTime createdAt) {
            this(chatMessageId, clientMessageId, userId, nickname, content, createdAt, null);
        }
    }
}
