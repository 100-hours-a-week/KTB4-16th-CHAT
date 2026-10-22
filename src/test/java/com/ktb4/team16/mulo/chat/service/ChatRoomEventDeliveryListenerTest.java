package com.ktb4.team16.mulo.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.ktb4.team16.mulo.chat.dto.response.ChatParticipantLeftResponse;
import com.ktb4.team16.mulo.chat.dto.response.ChatParticipantJoinedResponse;
import com.ktb4.team16.mulo.chat.dto.response.ChatParticipantNicknameChangedResponse;
import com.ktb4.team16.mulo.chat.event.ChatParticipantLeftDeliveryEvent;
import com.ktb4.team16.mulo.chat.event.ChatParticipantJoinedDeliveryEvent;
import com.ktb4.team16.mulo.chat.event.ChatParticipantNicknameChangedDeliveryEvent;
import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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

    @Test
    void nicknameDeliveryFailureDoesNotStopOtherRecipients() {
        ChatParticipantNicknameChangedResponse response = nicknameChangedResponse();
        doThrow(new IllegalStateException("broker unavailable"))
                .when(deliveryService).deliverParticipantNicknameChangedIfActive(30L, 28L, response);

        assertThatCode(() -> listener.deliverParticipantNicknameChanged(
                new ChatParticipantNicknameChangedDeliveryEvent(response, List.of(28L, 39L))))
                .doesNotThrowAnyException();

        verify(deliveryService).deliverParticipantNicknameChangedIfActive(30L, 39L, response);
    }

    @Test
    void nicknameDeliveryListenerRunsAfterTransactionCommit() throws NoSuchMethodException {
        Method listenerMethod = ChatRoomEventDeliveryListener.class.getMethod(
                "deliverParticipantNicknameChanged", ChatParticipantNicknameChangedDeliveryEvent.class);

        assertThat(listenerMethod.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
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

    private ChatParticipantNicknameChangedResponse nicknameChangedResponse() {
        return new ChatParticipantNicknameChangedResponse("PARTICIPANT_NICKNAME_CHANGED", 30L, 17L, "new-name");
    }
}
