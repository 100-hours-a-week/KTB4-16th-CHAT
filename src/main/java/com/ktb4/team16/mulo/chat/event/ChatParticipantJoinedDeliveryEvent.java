package com.ktb4.team16.mulo.chat.event;

import com.ktb4.team16.mulo.chat.dto.response.ChatParticipantJoinedResponse;
import java.util.List;

public record ChatParticipantJoinedDeliveryEvent(
        ChatParticipantJoinedResponse response,
        List<Long> recipientUserIds
) {

    public ChatParticipantJoinedDeliveryEvent {
        recipientUserIds = List.copyOf(recipientUserIds);
    }
}
