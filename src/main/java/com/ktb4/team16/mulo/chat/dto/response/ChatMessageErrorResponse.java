package com.ktb4.team16.mulo.chat.dto.response;

import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;

public record ChatMessageErrorResponse(
        String code,
        String message,
        UUID clientMessageId,
        @JsonInclude(JsonInclude.Include.NON_NULL) Debug debug
) {
    public ChatMessageErrorResponse(String code, String message, UUID clientMessageId) {
        this(code, message, clientMessageId, null);
    }

    public record Debug(String stage, String reason) {
    }
}
