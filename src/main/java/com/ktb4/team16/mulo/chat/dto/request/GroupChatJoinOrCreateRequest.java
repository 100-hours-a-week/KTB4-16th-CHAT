package com.ktb4.team16.mulo.chat.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record GroupChatJoinOrCreateRequest(
        @NotNull LocalDate groupDate,
        @NotNull @Positive Long voteQuestionId,
        @NotNull @Positive Long userId,
        @NotBlank @Size(max = 10) String nickname,
        @NotNull @Pattern(regexp = "A|B") String voteChoice
) {
}
