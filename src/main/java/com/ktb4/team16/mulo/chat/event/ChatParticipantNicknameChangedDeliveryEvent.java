package com.ktb4.team16.mulo.chat.event;

import com.ktb4.team16.mulo.chat.dto.response.ChatParticipantNicknameChangedResponse;
import java.util.List;

public record ChatParticipantNicknameChangedDeliveryEvent(
        ChatParticipantNicknameChangedResponse response,
        List<Long> recipientUserIds
) {

    public ChatParticipantNicknameChangedDeliveryEvent {
        recipientUserIds = List.copyOf(recipientUserIds);
    }
}
