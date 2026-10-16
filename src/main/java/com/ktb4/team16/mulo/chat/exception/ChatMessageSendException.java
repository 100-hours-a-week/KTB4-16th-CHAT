package com.ktb4.team16.mulo.chat.exception;

import java.util.UUID;

public class ChatMessageSendException extends RuntimeException {

    private final String code;
    private final UUID clientMessageId;

    public ChatMessageSendException(String code, String message, UUID clientMessageId) {
        super(message);
        this.code = code;
        this.clientMessageId = clientMessageId;
    }

    public String getCode() {
        return code;
    }

    public UUID getClientMessageId() {
        return clientMessageId;
    }

    public static ChatMessageSendException conflict(UUID clientMessageId) {
        return new ChatMessageSendException(
                "CLIENT_MESSAGE_ID_CONFLICT",
                "이미 다른 메시지에 사용된 clientMessageId입니다.",
                clientMessageId
        );
    }

    public static ChatMessageSendException persistenceFailure(UUID clientMessageId) {
        return new ChatMessageSendException(
                "STOMP_ERROR", "메시지를 전송할 수 없습니다.", clientMessageId);
    }
}
