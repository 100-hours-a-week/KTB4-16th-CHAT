package com.ktb4.team16.mulo.chat.exception;

import org.springframework.http.HttpStatus;

public class ChatApiException extends RuntimeException {

    private final ChatErrorCode errorCode;

    public ChatApiException(ChatErrorCode errorCode) {
        this(errorCode, errorCode.message());
    }

    public ChatApiException(ChatErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ChatErrorCode getErrorCode() {
        return errorCode;
    }

    public String getCode() {
        return errorCode.code();
    }

    public HttpStatus getStatus() {
        return errorCode.status();
    }
}
