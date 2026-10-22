package com.ktb4.team16.mulo.chat.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.ktb4.team16.mulo.chat.dto.response.ChatParticipantLeftResponse;
import com.ktb4.team16.mulo.chat.dto.response.ChatParticipantJoinedResponse;
import com.ktb4.team16.mulo.chat.event.ChatParticipantLeftDeliveryEvent;
import com.ktb4.team16.mulo.chat.event.ChatParticipantJoinedDeliveryEvent;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatRoomEventDeliveryListenerTest {

    private ChatRoomEventDeliveryService deliveryService;
    private ChatRoomEventDeliveryListener listener;

    @BeforeEach
    void setUp() {
        deliveryService = mock(ChatRoomEventDeliveryService.class);
        listener = new ChatRoomEventDeliveryListener(deliveryService);
    }

    @Test
    void attemptsDeliveryForEachRecipientAfterCommitCallback() {
        ChatParticipantLeftResponse response = response();

        listener.deliverParticipantLeft(new ChatParticipantLeftDeliveryEvent(response, List.of(28L)));

        verify(deliveryService).deliverParticipantLeftIfActive(30L, 28L, response);
    }

    @Test
    void deliveryFailureDoesNotEscapeTheAfterCommitListener() {
        ChatParticipantLeftResponse response = response();
        doThrow(new IllegalStateException("broker unavailable"))
                .when(deliveryService).deliverParticipantLeftIfActive(30L, 28L, response);

        assertThatCode(() -> listener.deliverParticipantLeft(
                new ChatParticipantLeftDeliveryEvent(response, List.of(28L))))
                .doesNotThrowAnyException();
    }

    @Test
    void attemptsJoinedEventDeliveryForEveryRecipient() {
        ChatParticipantJoinedResponse response = joinedResponse();

        listener.deliverParticipantJoined(new ChatParticipantJoinedDeliveryEvent(response, List.of(28L, 39L)));

        verify(deliveryService).deliverParticipantJoinedIfActive(30L, 28L, response);
        verify(deliveryService).deliverParticipantJoinedIfActive(30L, 39L, response);
    }

    @Test
    void joinedEventDeliveryFailureDoesNotEscapeOrStopOtherRecipients() {
        ChatParticipantJoinedResponse response = joinedResponse();
        doThrow(new IllegalStateException("broker unavailable"))
                .when(deliveryService).deliverParticipantJoinedIfActive(30L, 28L, response);

        assertThatCode(() -> listener.deliverParticipantJoined(
                new ChatParticipantJoinedDeliveryEvent(response, List.of(28L, 39L))))
                .doesNotThrowAnyException();

        verify(deliveryService).deliverParticipantJoinedIfActive(30L, 39L, response);
    }

    private ChatParticipantLeftResponse response() {
        return new ChatParticipantLeftResponse(
                "PARTICIPANT_LEFT", 30L, 17L, "sender",
                OffsetDateTime.of(2026, 10, 14, 15, 0, 0, 0, ZoneOffset.ofHours(9))
        );
    }

    private ChatParticipantJoinedResponse joinedResponse() {
        return new ChatParticipantJoinedResponse(
                "PARTICIPANT_JOINED", 30L, 17L, "sender", "B", 2,
                OffsetDateTime.of(2026, 10, 14, 15, 0, 0, 0, ZoneOffset.ofHours(9))
        );
    }
}
