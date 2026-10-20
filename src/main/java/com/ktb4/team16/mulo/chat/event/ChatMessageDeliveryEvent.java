package com.ktb4.team16.mulo.chat.event;

import com.ktb4.team16.mulo.chat.dto.response.ChatMessageResponse;
import java.util.List;

public record ChatMessageDeliveryEvent(ChatMessageResponse response, List<Long> recipientUserIds) {

    public ChatMessageDeliveryEvent {
        recipientUserIds = List.copyOf(recipientUserIds);
    }
}
