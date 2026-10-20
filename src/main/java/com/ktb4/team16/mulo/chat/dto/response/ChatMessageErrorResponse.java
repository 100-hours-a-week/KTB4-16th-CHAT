package com.ktb4.team16.mulo.chat.dto.response;

import java.util.UUID;

public record ChatMessageErrorResponse(String code, String message, UUID clientMessageId) {
}
