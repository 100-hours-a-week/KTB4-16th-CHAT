package com.ktb4.team16.mulo.chat.dto.response;

import java.time.OffsetDateTime;

public record ChatParticipantLeftResponse(
        String type,
        Long chatRoomId,
        Long userId,
        String nickname,
        OffsetDateTime leftAt
) {
}
