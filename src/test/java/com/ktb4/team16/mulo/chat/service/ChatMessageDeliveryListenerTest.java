package com.ktb4.team16.mulo.chat.service;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.ktb4.team16.mulo.chat.dto.response.ChatMessageResponse;
import com.ktb4.team16.mulo.chat.event.ChatMessageDeliveryEvent;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatMessageDeliveryListenerTest {

    private ChatMessageEventDeliveryService deliveryService;
    private ChatMessageDeliveryListener listener;

    @BeforeEach
    void setUp() {
        deliveryService = mock(ChatMessageEventDeliveryService.class);
        listener = new ChatMessageDeliveryListener(deliveryService);
    }

    @Test
    void deliversIndependentlyToEachRecipient() {
        ChatMessageResponse response = response();

        listener.deliver(new ChatMessageDeliveryEvent(response, List.of(17L, 28L)));

        verify(deliveryService).deliverIfActive(30L, 17L, response);
        verify(deliveryService).deliverIfActive(30L, 28L, response);
    }

    @Test
    void continuesToOtherRecipientsWhenOneDeliveryFails() {
        ChatMessageResponse response = response();
        doThrow(new IllegalStateException("broker unavailable"))
                .when(deliveryService).deliverIfActive(30L, 17L, response);

        listener.deliver(new ChatMessageDeliveryEvent(response, List.of(17L, 28L)));

        verify(deliveryService).deliverIfActive(30L, 17L, response);
        verify(deliveryService).deliverIfActive(30L, 28L, response);
    }

    private ChatMessageResponse response() {
        return new ChatMessageResponse(
                "MESSAGE", 30L, 101L, UUID.randomUUID(), 17L, "hello", OffsetDateTime.now()
        );
    }
}
