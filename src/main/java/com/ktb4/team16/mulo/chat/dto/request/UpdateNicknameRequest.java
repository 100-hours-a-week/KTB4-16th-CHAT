package com.ktb4.team16.mulo.chat.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateNicknameRequest(
        @NotBlank @Size(min = 1, max = 10) String nickname
) {
}
