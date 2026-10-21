package com.ktb4.team16.mulo.chat.dto.response;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record ChatRoomDetailResponse(
        Long chatRoomId,
        String roomType,
        LocalDate groupDate,
        Long voteQuestionId,
        Integer roomSequence,
        int capacity,
        int admittedParticipantCount,
        int activeParticipantCount,
        OffsetDateTime expiresAt,
        List<Participant> participants
) {

    public record Participant(Long userId, String nickname, String voteChoice, boolean hasLeft) {
    }
}
