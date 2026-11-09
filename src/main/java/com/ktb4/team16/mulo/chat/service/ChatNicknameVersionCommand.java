package com.ktb4.team16.mulo.chat.service;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** HTTP·메시지 수신 방식과 독립적인 닉네임 버전 적용 입력이다. */
public record ChatNicknameVersionCommand(
        @NotNull @Positive Long userId,
        @NotBlank @Size(min = 1, max = 10) String nickname,
        @PositiveOrZero long nicknameVersion,
        @NotNull UUID eventId) {
}
