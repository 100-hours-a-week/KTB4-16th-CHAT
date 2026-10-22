package com.ktb4.team16.mulo.chat.dto.response;

public record ChatParticipantNicknameChangedResponse(
        String type,
        Long chatRoomId,
        Long userId,
        String nickname
) {
}
