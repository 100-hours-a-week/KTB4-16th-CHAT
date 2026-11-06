package com.ktb4.team16.mulo.chat.dto.response;

import java.util.List;

/** 고정 상한까지 확인한 복구 페이지와 다음 ID를 전달한다. */
public record ChatMessageSyncResponse(
        List<ChatMessageHistoryResponse.Message> messages,
        long throughMessageId,
        long nextAfterMessageId,
        boolean hasNext) {}
