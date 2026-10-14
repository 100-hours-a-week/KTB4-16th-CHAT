package com.ktb4.team16.mulo.chat.dto.response;

import java.util.List;

public record ChatRoomDetailResponse(Long chatRoomId, List<Participant> participants) {

    public record Participant(Long userId, String nickname) {
    }
}
