package com.ktb4.team16.mulo.chat.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessageResponse(
        String type,
        Long chatRoomId,
        Long chatMessageId,
        UUID clientMessageId,
        Long userId,
        String content,
        OffsetDateTime createdAt,
        String voteChoice
) {
    public ChatMessageResponse(
            String type,
            Long chatRoomId,
            Long chatMessageId,
            UUID clientMessageId,
            Long userId,
            String content,
            OffsetDateTime createdAt
    ) {
        this(type, chatRoomId, chatMessageId, clientMessageId, userId, content, createdAt, null);
    }
}
