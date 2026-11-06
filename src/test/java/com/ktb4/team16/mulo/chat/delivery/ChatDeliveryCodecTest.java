package com.ktb4.team16.mulo.chat.delivery;

import static org.assertj.core.api.Assertions.*;

import com.ktb4.team16.mulo.chat.dto.response.ChatMessageResponse;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.*;

class ChatDeliveryCodecTest {
    @Test
    void validatesAndRoundTripsTypedMessage() {
        var codec = new ChatDeliveryCodec(JsonMapper.builder().findAndAddModules().build());
        var response =
                new ChatMessageResponse(
                        "MESSAGE",
                        1L,
                        2L,
                        UUID.randomUUID(),
                        17L,
                        "hello",
                        OffsetDateTime.now(),
                        null);
        var envelope = codec.create(response, List.of(17L, 18L, 17L));
        var decoded = codec.decode(codec.encode(envelope));
        assertThat(decoded.recipientUserIds()).containsExactly(17L, 18L);
        assertThat(((ChatMessageResponse) codec.response(decoded)).content()).isEqualTo("hello");
        assertThatThrownBy(
                        () ->
                                codec.decode(
                                        codec.encode(envelope)
                                                .replace(
                                                        "\"schemaVersion\":1",
                                                        "\"schemaVersion\":2")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode("x".repeat(262145)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesAllRoomEventPayloadsAndRejectsRoomMismatch() {
        var codec = new ChatDeliveryCodec(JsonMapper.builder().findAndAddModules().build());
        var now = OffsetDateTime.now();
        java.util.List<Object> events =
                java.util.List.of(
                        new com.ktb4.team16.mulo.chat.dto.response.ChatParticipantJoinedResponse(
                                "PARTICIPANT_JOINED", 1L, 17L, "one", "A", 2, now),
                        new com.ktb4.team16.mulo.chat.dto.response.ChatParticipantLeftResponse(
                                "PARTICIPANT_LEFT", 1L, 17L, "one", now),
                        new com.ktb4.team16.mulo.chat.dto.response
                                .ChatParticipantNicknameChangedResponse(
                                "PARTICIPANT_NICKNAME_CHANGED", 1L, 17L, "new"));
        for (Object event : events) {
            var json = codec.encode(codec.create(event, java.util.List.of(18L)));
            var decoded = codec.decode(json);
            assertThat(codec.response(decoded)).isEqualTo(event);
        }
        var envelope = codec.create(events.getFirst(), java.util.List.of(18L));
        assertThatThrownBy(
                        () ->
                                codec.encode(
                                        new ChatDeliveryEnvelope(
                                                1,
                                                envelope.eventId(),
                                                envelope.type(),
                                                99L,
                                                envelope.recipientUserIds(),
                                                envelope.payload())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
