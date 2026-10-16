package com.ktb4.team16.mulo.chat.exception;

import java.util.UUID;

public class ChatMessageSendException extends RuntimeException {

    private final ChatErrorCode errorCode;
    private final UUID clientMessageId;

    public ChatMessageSendException(ChatErrorCode errorCode, UUID clientMessageId) {
        super(errorCode.message());
        this.errorCode = errorCode;
        this.clientMessageId = clientMessageId;
    }

    public ChatMessageSendException(ChatErrorCode errorCode, String message, UUID clientMessageId) {
        super(message);
        this.errorCode = errorCode;
        this.clientMessageId = clientMessageId;
    }

    public String getCode() {
        return errorCode.code();
    }

    public ChatErrorCode getErrorCode() {
        return errorCode;
    }

    public UUID getClientMessageId() {
        return clientMessageId;
    }

    public static ChatMessageSendException conflict(UUID clientMessageId) {
        return new ChatMessageSendException(ChatErrorCode.CLIENT_MESSAGE_ID_CONFLICT, clientMessageId);
    }

    public static ChatMessageSendException persistenceFailure(UUID clientMessageId) {
        return new ChatMessageSendException(ChatErrorCode.STOMP_ERROR, clientMessageId);
    }
}
