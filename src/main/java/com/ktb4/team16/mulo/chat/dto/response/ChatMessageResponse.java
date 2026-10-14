package com.ktb4.team16.mulo.chat.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ChatMessageResponse(
        Long chatMessageId,
        Long chatRoomId,
        Long userId,
        UUID clientMessageId,
        String content,
        OffsetDateTime createdAt
) {
}
