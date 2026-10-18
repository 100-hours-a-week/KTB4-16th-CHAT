package com.ktb4.team16.mulo.chat.moderation.dto;

public record ChatModerationResponse(
        String requestId,
        Boolean isToxic,
        Double confidence,
        String category,
        String modelVersion
) {
}
