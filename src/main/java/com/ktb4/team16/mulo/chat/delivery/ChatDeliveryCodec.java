package com.ktb4.team16.mulo.chat.delivery;

import com.ktb4.team16.mulo.chat.dto.response.*;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 허용한 네 응답만 역직렬화하며 임의 클래스 정보를 신뢰하지 않는다. */
public final class ChatDeliveryCodec {
    private final JsonMapper mapper;

    /** 애플리케이션의 날짜 직렬화 설정을 재사용한다. */
    public ChatDeliveryCodec(JsonMapper mapper) {
        // 태스크 간 재직렬화로 기존 KST/OffsetDateTime 계약을 UTC로 바꾸지 않는다.
        this.mapper =
                mapper.rebuild()
                        .disable(
                                tools.jackson.databind.cfg.DateTimeFeature
                                        .ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                        .disable(
                                tools.jackson.databind.cfg.DateTimeFeature
                                        .WRITE_DATES_WITH_CONTEXT_TIME_ZONE)
                        .build();
    }

    /** 기존 응답을 내부 envelope로 만들고 수신자를 정규화한다. */
    public ChatDeliveryEnvelope create(Object response, List<Long> recipients) {
        var node = mapper.valueToTree(response);
        var envelope =
                new ChatDeliveryEnvelope(
                        1,
                        UUID.randomUUID(),
                        node.path("type").asString(),
                        node.path("chatRoomId").asLong(),
                        recipients.stream().distinct().toList(),
                        node);
        validate(envelope);
        return envelope;
    }

    /** 제한 크기의 JSON만 발행한다. */
    public String encode(ChatDeliveryEnvelope envelope) {
        validate(envelope);
        var json = mapper.writeValueAsString(envelope);
        checkSize(json);
        return json;
    }

    /** 외부 JSON의 계약을 검증하고 버전 오류를 거절한다. */
    public ChatDeliveryEnvelope decode(String json) {
        try {
            checkSize(json);
            var envelope = mapper.readValue(json, ChatDeliveryEnvelope.class);
            validate(envelope);
            return envelope;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid chat delivery envelope", error);
        }
    }

    /** type allowlist에 지정된 DTO로만 payload를 복원한다. */
    public Object response(ChatDeliveryEnvelope e) {
        return switch (e.type()) {
            case "MESSAGE" -> mapper.treeToValue(e.payload(), ChatMessageResponse.class);
            case "PARTICIPANT_JOINED" ->
                    mapper.treeToValue(e.payload(), ChatParticipantJoinedResponse.class);
            case "PARTICIPANT_LEFT" ->
                    mapper.treeToValue(e.payload(), ChatParticipantLeftResponse.class);
            case "PARTICIPANT_NICKNAME_CHANGED" ->
                    mapper.treeToValue(e.payload(), ChatParticipantNicknameChangedResponse.class);
            default -> throw new IllegalArgumentException("Unsupported chat delivery type");
        };
    }

    /** 내부 계약과 기존 DTO 필드의 모순을 차단한다. */
    private void validate(ChatDeliveryEnvelope e) {
        if (e == null
                || e.schemaVersion() != 1
                || e.eventId() == null
                || e.chatRoomId() == null
                || e.chatRoomId() <= 0
                || e.recipientUserIds() == null
                || e.recipientUserIds().size() > 100
                || e.recipientUserIds().stream().anyMatch(id -> id == null || id <= 0)
                || e.payload() == null
                || !Objects.equals(e.type(), e.payload().path("type").asString())
                || e.chatRoomId() != e.payload().path("chatRoomId").asLong())
            throw new IllegalArgumentException("Invalid chat delivery contract");
        Object dto = response(e);
        if (dto instanceof ChatMessageResponse m
                && (m.chatMessageId() == null
                        || m.chatMessageId() <= 0
                        || m.clientMessageId() == null
                        || m.userId() == null
                        || m.content() == null
                        || m.createdAt() == null))
            throw new IllegalArgumentException("Invalid message payload");
    }

    /** 크기 제한은 문자 수가 아닌 실제 전송 바이트에 적용한다. */
    private void checkSize(String json) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > 262144)
            throw new IllegalArgumentException("Chat delivery payload exceeds limit");
    }
}
