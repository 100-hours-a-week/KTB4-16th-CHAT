package com.ktb4.team16.mulo.chat.moderation.dto;

public record ChatModerationRequest(String requestId, String roomId, String userId, String text) {
}
