package com.ktb4.team16.mulo.chat.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

public record CreateChatRoomRequest(
        @NotNull @Positive Long matchId,
        @NotNull OffsetDateTime expiresAt,
        @NotNull @Size(min = 2, max = 2) List<@Valid Participant> participants
) {

    public record Participant(
            @NotNull @Positive Long userId,
            @NotBlank @Size(min = 1, max = 10) String nickname
    ) {
    }
}
