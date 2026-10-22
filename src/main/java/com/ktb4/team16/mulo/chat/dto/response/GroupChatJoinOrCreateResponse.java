package com.ktb4.team16.mulo.chat.dto.response;

import java.time.OffsetDateTime;

public record GroupChatJoinOrCreateResponse(
        Long chatRoomId,
        int roomSequence,
        boolean newlyAssigned,
        int admittedParticipantCount,
        int activeParticipantCount,
        OffsetDateTime expiresAt
) {
}
