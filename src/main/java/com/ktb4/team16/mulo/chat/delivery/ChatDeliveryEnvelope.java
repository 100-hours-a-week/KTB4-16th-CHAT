package com.ktb4.team16.mulo.chat.delivery;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

/** 태스크 사이에서만 사용하는 버전 지정 전달 계약이다. */
public record ChatDeliveryEnvelope(
        int schemaVersion,
        UUID eventId,
        String type,
        Long chatRoomId,
        List<Long> recipientUserIds,
        JsonNode payload) {}
