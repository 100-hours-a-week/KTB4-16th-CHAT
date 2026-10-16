package com.ktb4.team16.mulo.chat.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ChatMessageResponse(
        String type,
        Long chatRoomId,
        Long chatMessageId,
        UUID clientMessageId,
        Long userId,
        String content,
        OffsetDateTime createdAt
) {
}
