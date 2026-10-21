package com.ktb4.team16.mulo.chat.dto.response;

import java.time.OffsetDateTime;

public record ChatParticipantJoinedResponse(
        String type,
        Long chatRoomId,
        Long userId,
        String nickname,
        String voteChoice,
        int activeParticipantCount,
        OffsetDateTime occurredAt
) {
}
