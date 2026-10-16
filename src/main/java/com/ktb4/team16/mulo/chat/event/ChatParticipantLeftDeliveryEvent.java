package com.ktb4.team16.mulo.chat.event;

import com.ktb4.team16.mulo.chat.dto.response.ChatParticipantLeftResponse;
import java.util.List;

public record ChatParticipantLeftDeliveryEvent(
        ChatParticipantLeftResponse response,
        List<Long> recipientUserIds
) {

    public ChatParticipantLeftDeliveryEvent {
        recipientUserIds = List.copyOf(recipientUserIds);
    }
}
