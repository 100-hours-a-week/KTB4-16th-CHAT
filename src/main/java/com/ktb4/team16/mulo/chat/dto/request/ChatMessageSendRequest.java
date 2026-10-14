package com.ktb4.team16.mulo.chat.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record ChatMessageSendRequest(
        @NotNull UUID clientMessageId,
        @NotBlank @Size(max = 500) String content
) {
}
